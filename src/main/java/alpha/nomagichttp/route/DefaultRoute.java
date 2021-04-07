package alpha.nomagichttp.route;

import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.MediaRange;
import alpha.nomagichttp.message.MediaType;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static alpha.nomagichttp.message.MediaType.__ALL;
import static alpha.nomagichttp.message.MediaType.__NOTHING;
import static alpha.nomagichttp.message.MediaType.__NOTHING_AND_ALL;
import static alpha.nomagichttp.message.MediaType.Score.NOPE;
import static alpha.nomagichttp.route.AmbiguousNoHandlerFoundException.createAmbiguousEx;
import static java.lang.String.join;
import static java.text.MessageFormat.format;
import static java.util.Arrays.stream;
import static java.util.Comparator.comparingDouble;
import static java.util.Comparator.comparingInt;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toSet;

/**
 * Default implementation of {@link Route}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class DefaultRoute implements Route
{
    // TODO: Consider replacing with array[]
    private final Iterable<String> segments;
    // TODO: Consider replacing value type with array[]
    private final Map<String, List<RequestHandler>> handlers;
    
    /**
     * Constructs a {@code DefaultRoute}.<p>
     * 
     * The segments are assumed to have been validated already (by the builder).
     * 
     * @param segments  of the route
     * @param handlers  of the route
     * 
     * @throws NullPointerException   if any argument is {@code null}
     * @throws IllegalStateException  if {@code handlers} is empty
     */
    private DefaultRoute(Iterable<String> segments, Set<RequestHandler> handlers) {
        if (handlers.isEmpty()) {
            throw new IllegalStateException("No handlers.");
        }
        
        this.segments = segments;
        this.handlers = handlers.stream().collect(groupingBy(RequestHandler::method));
    }
    
    @Override
    public Iterable<String> segments() {
        return segments;
    }
    
    @Override
    public RequestHandler lookup(
            String method,
            MediaType contentType,
            MediaType[] accepts)
    {
        List<RequestHandler> forMethod = filterByMethod(method, contentType, accepts);
        
        NavigableSet<RankedHandler> candidates = null;
        Set<RankedHandler> ambiguous = null;
        
        for (RequestHandler h : forMethod) {
            if (!filterByContentType(h, contentType)) {
                continue;
            }
            
            RankedHandler r = filterByAccept(h, accepts);
            if (r == null) {
                continue;
            }
            
            if (candidates == null) {
                candidates = new TreeSet<>();
            }
            
            if (!candidates.add(r)) {
                if (ambiguous == null) {
                    // Initialize with the other ambiguous handler before adding this one.
                    ambiguous = new HashSet<>(candidates.subSet(r, true, r, true));
                }
                ambiguous.add(r);
            }
        }
        
        if (candidates == null) {
            throw NoHandlerFoundException.unmatchedContentType(
                    method, this, contentType, accepts);
        }
        
        if (ambiguous != null) {
            // Try to solve
            candidates.removeAll(ambiguous);
            
            if (candidates.isEmpty()) {
                Set<RequestHandler> unwrapped = ambiguous.stream().map(RankedHandler::handler).collect(toSet());
                throw createAmbiguousEx(unwrapped, method, this, contentType, accepts);
            }
        }
        
        return candidates.first().handler();
    }
    
    /**
     * Return all handlers of the specified method, or throws
     * {@code NoHandlerFoundException}.
     */
    private List<RequestHandler> filterByMethod(
            String method,
            MediaType contentType,
            MediaType[] accepts)
    {
        final List<RequestHandler> rh = handlers.get(method);
        
        if (rh == null) {
            throw NoHandlerFoundException.unmatchedMethod(
                    method, this, contentType, accepts);
        }
        
        assert !rh.isEmpty();
        return rh;
    }
    
    /**
     * Returns {@code true} if the specified handler consumes the specified
     * content-type, otherwise {@code false}.
     */
    private static boolean filterByContentType(
            RequestHandler handler,
            MediaType contentType)
    {
        final MediaType consumes = handler.consumes();
        
        if (consumes == __NOTHING_AND_ALL) {
            return true;
        }
        
        boolean contentTypeProvided = contentType != null,
                consumesNothing     = consumes == __NOTHING;
        
        if (contentTypeProvided) {
            if (consumesNothing) {
                return false;
            }
            
            return consumes.compatibility(contentType) != NOPE;
        }
        else return consumesNothing;
    }
    
    /**
     * Returns a {@link RankedHandler} if the specified handler produces any one
     * of the accepted media types, otherwise {@code null}.<p>
     * 
     * The rank will be the client-provided quality/weight associated with the
     * most specific media type for which the handler is compatible with.
     */
    // TODO: Should probably instead of using Q of the most specific type use
    //       the greatest Q of any compatible type discovered?
    private static RankedHandler filterByAccept(RequestHandler handler, MediaType[] accepts) {
        final MediaType produces = handler.produces();
        
        if (accepts == null || accepts.length == 0) {
            // If accept is not provided, the default is "*/*; q=1".
            return produces.parameters().isEmpty() ?
                    new RankedHandler(1., handler) : null;
        }
        
        Optional<MediaType> opt = stream(accepts)
                    .filter(a -> produces.compatibility(a) != NOPE)
                    .min(comparingInt(MediaType::specificity));
        
        if (opt.isEmpty()) {
            return null;
        }
        
        MediaType specific = opt.get();
        
        double q = specific instanceof MediaRange ?
                ((MediaRange) specific).quality() : 1.;
        
        return new RankedHandler(q, handler);
    }
    
    @Override
    public String toString() {
        return '/' + join("/", segments);
    }
    
    /**
     * A {@code Comparable} handler based primarily on a rank, comparing
     * secondly the specificity of the handler's produces media type and
     * comparing thirdly the specificity of the handler's consuming media type.
     */
    private static final class RankedHandler implements Comparable<RankedHandler> {
        private static final Comparator<RankedHandler> ORDER =
                comparingDouble(RankedHandler::rank).reversed()
              .thenComparingInt(RankedHandler::specificityOfProduces)
              .thenComparingInt(RankedHandler::specificityOfConsumes);
        
        private final double rank;
        
        private final RequestHandler handler;
        
        private final int specificityOfProduces,
                          specificityOfConsumes,
                          hash;
        
        RankedHandler(double /* quality: */ rank, RequestHandler handler) {
            this.rank = rank;
            this.handler = handler;
            this.specificityOfProduces = handler.produces().specificity();
            this.specificityOfConsumes = handler.consumes().specificity();
            this.hash = Double.hashCode(rank) + handler.hashCode();
        }
        
        private double rank() {
            return rank;
        }
        
        private int specificityOfProduces() {
            return specificityOfProduces;
        }
        
        private int specificityOfConsumes() {
            return specificityOfConsumes;
        }
        
        public RequestHandler handler() {
            return handler;
        }
        
        @Override
        public int compareTo(RankedHandler o) {
            return ORDER.compare(this, o);
        }
        
        @Override
        public int hashCode() {
            return hash;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            
            if (obj == null) {
                return false;
            }
            
            if (RankedHandler.class != obj.getClass()) {
                return false;
            }
            
            RankedHandler that = (RankedHandler) obj;
            
            return this.rank == that.rank &&
                   this.handler.equals(that.handler);
        }
    
        @Override
        public String toString() {
            return "{" + join(", ",
                      "cons=" + handler.consumes(),
                      "prod=" + handler.produces(),
                      "rank=" + rank) +
                    "}";
        }
    }
    
    /**
     * Default implementation of {@code Route.Builder}.
     * 
     * @author Martin Andersson (webmaster at martinandersson.com)
     */
    static final class Builder implements Route.Builder
    {
        private final SegmentsBuilder segments;
        private final Set<RequestHandler> handlers;
        
        Builder(String pattern) {
            segments = new SegmentsBuilder();
            handlers = new HashSet<>();
            
            if (!pattern.equals("/")) {
                try {
                    append(pattern);
                } catch (IllegalArgumentException | IllegalStateException e) {
                    throw new RouteParseException(e);
                }
            }
        }
        
        @Override
        public Route.Builder paramSingle(String name) {
            segments.paramSingle(name);
            return this;
        }
        
        @Override
        public Route.Builder paramCatchAll(String name) {
            segments.paramCatchAll(name);
            return this;
        }
        
        @Override
        public Route.Builder append(String p) {
            segments.append(p);
            return this;
        }
        
        private static final Set<MediaType> SPECIAL = Set.of(__NOTHING, __NOTHING_AND_ALL, __ALL);
        
        @Override
        public Route.Builder handler(RequestHandler first, RequestHandler... more) {
            if (SPECIAL.contains(first.consumes())) {
                Set<MediaType> specials = handlers.stream()
                        .filter(h -> h.method().equals(first.method()))
                        .filter(h -> h.produces().equals(first.produces()))
                        .map(RequestHandler::consumes)
                        .filter(SPECIAL::contains)
                        .collect(toCollection(HashSet::new));
                
                specials.add(first.consumes());
                
                if (specials.equals(SPECIAL)) {
                    throw new HandlerCollisionException(format(
                            "All other meta data being equal; if there''s a consumes {0} then {1} is effectively equal to {2}.",
                            __NOTHING, __NOTHING_AND_ALL, __ALL));
                }
            }
            
            if (!handlers.add(requireNonNull(first))) {
                throw new HandlerCollisionException(
                        "An equivalent handler has already been added: " + first);
            }
            
            stream(more).forEach(this::handler);
            
            return this;
        }
        
        @Override
        public Route build() {
            return new DefaultRoute(segments.asIterable(), handlers);
        }
    }
}