package alpha.nomagichttp.route;

import alpha.nomagichttp.handler.Handler;
import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.Request;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static alpha.nomagichttp.message.MediaType.ALL;
import static alpha.nomagichttp.message.MediaType.NOTHING;
import static alpha.nomagichttp.message.MediaType.WHATEVER;
import static java.text.MessageFormat.format;
import static java.util.Arrays.stream;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toCollection;

/**
 * Builds the default implementation of a {@link Route}.
 * 
 * <h3>Usage</h3>
 * 
 * For example, this:<p>
 * <pre>{@code
 *     Handler h = ...
 *     new RouteBuilder("/hello/world").handler(h).build()
 * }</pre>
 * 
 * ...will match a request that has a path (request-target) "/hello/world".<p>
 * 
 * There are no magical string patterns, rules, regexes or RFC specifications
 * that needs to be understood in order to declare path parameters. They are
 * declared using a method:
 * <pre>
 *     new RouteBuilder("/hello").param("name")
 * </pre>
 * 
 * The parameter value for "name" (if provided in the request) will be
 * accessible through the {@link Request request} object passed to the
 * handler.<p>
 * 
 * It is possible to keep building on the path after parameters using {@link
 * #concat(String)}.<p>
 *  
 * TODO: Elaborative example.<p>
 * 
 * All parameters (path and query) are optional. If they are not provided in the
 * request, nor will they be available in the request object.<p>
 * 
 * For example, this:
 * <pre>
 *     new RouteBuilder("/hello").param("blabla").concat("/something)
 * </pre>
 * 
 * Will match request-target "/hello/my-value/something". {@link
 * Request#paramFromPath(String) Request.paramFromPath("blabla")} will return
 * "my-value". The same route will also match "/hello/something" except in this
 * case the {@code paramFromPath()} will return an empty optional.<p>
 * 
 * Because parameters are optional, they can not be used to distinguish between
 * differnent routes. Adding this route to a registry which already has the
 * former registered will throw a {@link RouteCollisionException}:
 * <pre>
 *     new RouteBuilder("/hello/something")
 * </pre>
 * 
 * 
 * <h3>Valid and not valid string inputs</h3>
 * 
 * The segments added to a route is required to start with a forward slash
 * character ('/'). They may or may not end with a forward slash. Only the first
 * segment added through the RouteBuilder constructor can be a single forward
 * slash character.<p>
 * 
 * A parameter name is any valid string. This includes really weird stuff like
 * an empty string, brackets or even a single forward slash character - the
 * parameter name is just a string.<p>
 * 
 * 
 * <h3>Thread safety, life cycle and identity</h3>
 * 
 * This class is not thread-safe.<p>
 * 
 * The intent of this class is to be a short-lived "throw-away" builder with
 * no references to the builder instances being saved or passed around.<p>
 * 
 * It's fully possible to keep mutating the same builder instance to create new
 * routes using the "old" as template, but this is probably only useful for
 * testing purposes.<p>
 * 
 * 
 * @author Martin Andersson (mandersson at martinandersson.com)
 */
public class RouteBuilder
{
    private final List<SegmentBuilder> segments;
    // Memorize what param names we have already used
    private final Set<String> params;
    private final Set<Handler> handlers;
    
    /**
     * Constructs a {@code RouteBuilder} initialized with a segment.
     * 
     * @param segment initial segment
     * 
     * @throws IllegalArgumentException
     *           if {@code segment} is not valid (see {@linkplain RouteBuilder})
     */
    public RouteBuilder(final String segment) {
        SegmentBuilder root = new SegmentBuilder(segment, true);
        
        segments = new ArrayList<>();
        segments.add(root);
        
        params   = new HashSet<>();
        handlers = new HashSet<>();
    }
    
    /**
     * Declare one or many named path parameters.
     * 
     * @param firstName  first name
     * @param moreNames  more names
     * 
     * @return this (for chaining/fluency)
     * 
     * @throws NullPointerException
     *             if anyone of the provided names is {@code null}
     *
     * @throws IllegalArgumentException
     *           if {@code segment} is not valid (see {@linkplain RouteBuilder})
     */
    public RouteBuilder param(String firstName, String... moreNames) {
        if (!params.add(firstName)) {
            throw new IllegalArgumentException(
                    "Duplicate parameter name: \"" + firstName + "\"");
        }
        
        segments.get(segments.size() - 1).addParam(firstName);
        
        if (moreNames.length > 0) {
            stream(moreNames).forEach(this::param);
        }
        
        return this;
    }
    
    // TODO: Docs
    public RouteBuilder concat(final String segment) {
        SegmentBuilder last = segments.get(segments.size() - 1);
    
        // If no params were registered for the last part, keep building on it.
        if (!last.hasParams()) {
            last.concat(segment);
        }
        else {
            segments.add(new SegmentBuilder(segment, false));
        }
        
        return this;
    }
    
    private static final Set<MediaType> SPECIAL = Set.of(NOTHING, WHATEVER, ALL);
    
    // TODO: Docs
    public RouteBuilder handler(Handler first, Handler... more) {
        if (SPECIAL.contains(first.consumes())) {
            Set<MediaType> specials = handlers.stream()
                    .filter(h -> h.method().equals(first.method()))
                    .filter(h -> h.produces().equals(first.produces()))
                    .map(Handler::consumes)
                    .filter(SPECIAL::contains)
                    .collect(toCollection(HashSet::new));
            
            specials.add(first.consumes());
            
            if (specials.equals(SPECIAL)) {
                throw new HandlerCollisionException(format(
                        "All other meta data being equal; if there''s a consumes {0} then {1} is effectively equal to {2}.",
                        NOTHING, WHATEVER, ALL));
            }
        }
        
        if (!handlers.add(requireNonNull(first))) {
            throw new HandlerCollisionException(
                    "An equivalent handler has already been added: " + first);
        }
        
        stream(more).forEach(this::handler);
        
        return this;
    }
    
    // TODO: Docs
    public Route build() {
        return new DefaultRoute(segments, handlers);
    }
}