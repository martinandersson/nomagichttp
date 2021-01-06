package alpha.nomagichttp.route;

import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.MediaRange;
import alpha.nomagichttp.message.MediaType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static alpha.nomagichttp.message.MediaType.ALL;
import static alpha.nomagichttp.message.MediaType.NOTHING;
import static alpha.nomagichttp.message.MediaType.NOTHING_AND_ALL;
import static alpha.nomagichttp.message.MediaType.Score.NOPE;
import static alpha.nomagichttp.route.AmbiguousNoHandlerFoundException.createAmbiguousEx;
import static java.lang.System.Logger;
import static java.lang.System.Logger.Level.WARNING;
import static java.text.MessageFormat.format;
import static java.util.Arrays.stream;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;
import static java.util.Comparator.comparingDouble;
import static java.util.Comparator.comparingInt;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Collectors.toUnmodifiableList;

/**
 * Default implementation of {@link Route}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class DefaultRoute implements Route
{
    private static final Logger LOG = System.getLogger(DefaultRoute.class.getPackageName());
    
    // TODO: Consider replacing with array[]
    private final List<Segment> segments;
    private final String identity;
    // TODO: Consider replacing value type with array[]
    private final Map<String, List<RequestHandler>> handlers;
    
    /**
     * Constructs a {@code DefaultRoute}.
     * 
     * The given arguments does not have to be unmodifiable as the collections
     * will be copied. 
     * 
     * @param segments  of the route
     * @param handlers  of the route
     * 
     * @throws NullPointerException   if any argument is {@code null}
     * @throws IllegalStateException  if {@code handlers} is empty
     */
    private DefaultRoute(List<MutableSegment> segments, Set<RequestHandler> handlers) {
        if (handlers.isEmpty()) {
            throw new IllegalStateException("No handlers.");
        }
        
        this.segments = segments.stream().collect(toUnmodifiableList());
        this.identity = computeIdentity();
        this.handlers = handlers.stream().collect(groupingBy(RequestHandler::method));
    }
    
    private String computeIdentity() {
        String withoutRoot = segments.stream()
                .map(Segment::value)
                .filter(v -> v.length() > 1)
                .collect(joining());
        
        return withoutRoot.isEmpty() ? "/" : withoutRoot;
    }
    
    @Override
    public Match matches(final String requestTarget) {
        final String parse = ensureSingleSlashWrap(requestTarget);
        
        // If we have only one segment with no params, then all of it must match.
        if (segments.size() == 1 && segments.get(0).params().isEmpty()) {
            String seg = ensureSingleSlashWrap(segments.get(0).value());
            return parse.equals(seg) ? new DefaultMatch(Map.of()) : null;
        }
        
        LinkedHashMap<Segment, Integer> indices = segmentIndices(parse);
        if (indices == null) {
            return null;
        }
        
        Map<String, String> params = extractParameters(parse, indices);
        if (params == null) {
            return null;
        }
        
        return new DefaultMatch(params);
    }
    
    /**
     * Find all segment indices in the provided request-target.<p>
     * 
     * If not all segments are found, {@code null} is returned.<p>
     * 
     * If all segments are found, then this route can be assumed to match the
     * provided request-target and the indices will be used as offsets for the
     * operation that extracts parameter values.
     * 
     * @return all segment indices in the provided request-target
     */
    private LinkedHashMap<Segment, Integer> segmentIndices(String parse) {
        LinkedHashMap<Segment, Integer> indices = null;
        Segment last = null;
        
        for (Segment seg : segments) {
            // Start from the last hit. For two reasons:
            //   1) No need to search the input string all over again.
            //   2) It's actually required that the segments follow each other orderly.
            int from = last == null ? 0 :
                    indices.get(last) + last.value().length();
            
            // If last segment was root "/", push back the position by 1 (all segments starts with '/')
            if (last != null && last.isFirst()) {
                from--;
            }
            
            last = seg;
            
            // Need slash ending, otherwise route "/blabla" could match request-target "/blabla-more".
            int indexOf = parse.indexOf(ensureSingleSlashWrap(seg.value()), from);
            
            if (indexOf == -1) {
                // All segments must match.
                return null;
            }
            
            // Match found, save position.
            if (indices == null) {
                indices = new LinkedHashMap<>();
            }
            indices.put(seg, indexOf);
        }
        
        assert indices != null;
        return indices;
    }
    
    /**
     * Extract path parameters from the request-target.<p>
     * 
     * Values will be extracted after each segment but before the next one
     * starts.<p>
     * 
     * This method may return {@code null} in the event a trailing segment was
     * discovered which is not a recognized parameter - in which case this route
     * is not a match.
     * 
     * @return parameter name to value (modifiable)
     */
    private static Map<String, String> extractParameters(String parse, LinkedHashMap<Segment, Integer> indices) {
        final Map<String, String> paramToValue = new HashMap<>();
        
        Iterator<Map.Entry<Segment, Integer>>
                current = indices.entrySet().iterator(),
                peek    = indices.entrySet().iterator();
        
        peek.next();
        
        while (current.hasNext()) {
            Map.Entry<Segment, Integer>
                    c = current.next(),
                    p = peek.hasNext() ? peek.next() : null;
            
            // TODO: Probably want to refactor into an extractSegmentValues()
            
            Segment s = c.getKey();
            List<String> paramNames = s.params();
            
            if (paramNames.isEmpty()) {
                // Segment has no params associated, skip
                continue;
            }
            
            // Start extracting param values from position after segment
            int from = c.getValue() + s.value().length(),
                // Stop extracting at the next segment start or end of input if this is the last segment
                to   = p == null ? parse.length() : p.getValue();
            
            if (to < from) {
                // Next segment starts immediately after root, can not extract params.
                continue;
            }
            
            // This is the piece that we need to extract param values from.
            final String input = parse.substring(from, to);
            
            // input can have trailing and ending forward slashes, yielding the empty string.
            // Filter them out.
            Iterator<String> values = stream(input.split("/"))
                    .filter(s0 -> !s0.isEmpty())
                    .iterator();
            
            // Values now map in order. We log the presence of unknown values.
            for (String k : paramNames) {
                if (!values.hasNext()) {
                    // Param is optional and value not provided
                    continue;
                }
                paramToValue.put(k, values.next());
            }
            
            if (values.hasNext()) {
                if (!current.hasNext()) {
                    // No more segments and no more params to parse,
                    // so the next "value" is an unrecognized segment
                    return null;
                }
                else {
                    // There's still more segments to be evaluated,
                    // so remaining values are unknown param values to the current segment
                    LOG.log(WARNING, () -> "Segment \"" + s + "\" received unknown parameter value(s).");
                }
            }
        }
        
        return unmodifiableMap(paramToValue);
    }
    
    @Override
    public Iterable<Segment> segments() {
        return segments;
    }
    
    @Override
    public RequestHandler lookup(String method, MediaType contentType, MediaType[] accepts) {
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
     * Returns all handlers of the specified method, or throws {@code NoHandlerFoundException}.
     */
    private List<RequestHandler> filterByMethod(String method, MediaType contentType, MediaType[] accepts) {
        final List<RequestHandler> forMethod = handlers.get(method);
        
        if (forMethod == null) {
            throw NoHandlerFoundException.unmatchedMethod(
                    method, this, contentType, accepts);
        }
        
        assert !forMethod.isEmpty();
        return forMethod;
    }
    
    /**
     * Returns {@code true} if the specified handler consumes the specified
     * content-type, otherwise {@code false}.
     */
    private static boolean filterByContentType(RequestHandler handler, MediaType contentType) {
        final MediaType consumes = handler.consumes();
        
        if (consumes == NOTHING_AND_ALL) {
            return true;
        }
        
        boolean contentTypeProvided = contentType != null,
                consumesNothing     = consumes == NOTHING;
        
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
    public String identity() {
        return identity;
    }
    
    @Override
    public int hashCode() {
        return identity.hashCode();
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        
        if (obj == null) {
            return false;
        }
        
        if (DefaultRoute.class != obj.getClass()) {
            return false;
        }
        
        DefaultRoute that = (DefaultRoute) obj;
        
        return this.identity.equals(that.identity);
    }
    
    @Override
    public String toString() {
        return segments.stream().map(Object::toString).collect(joining());
    }
    
    private static String ensureSingleSlashWrap(String path) {
        if (path.isEmpty()) {
            return "/";
        }
        
        if (path.equals("/") || path.equals("//")) {
            return path;
        }
        
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        
        if (!path.endsWith("/")) {
            path += "/";
        }
        
        return path;
    }
    
    private final class DefaultMatch implements Route.Match {
        private final Map<String, String> params;
        
        /**
         * Constructs a {@code DefaultMap}.<p>
         * 
         * @param params from path (assumed to be unmodifiable)
         */
        DefaultMatch(Map<String, String> params) {
            this.params = params;
        }
        
        @Override
        public Route route() {
            return DefaultRoute.this;
        }
    
        @Override
        public Map<String, String> parameters() {
            return params;
        }
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
            return "{" + String.join(", ",
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
        private static final int INITIAL_CAPACITY = 3;
        
        private final List<MutableSegment> segments;
        private final Set<String> params;
        private final Set<RequestHandler> handlers;
        
        Builder(String segment) {
            segments = new ArrayList<>(INITIAL_CAPACITY);
            params   = new HashSet<>();
            handlers = new HashSet<>();
            
            // all route paths start with the root
            segments.add(new MutableSegment(""));
            
            if (!segment.equals("/")) {
                append(segment);
            }
        }
        
        @Override
        public Route.Builder param(String firstName, String... moreNames) {
            if (!params.add(firstName)) {
                throw new IllegalArgumentException(
                        "Duplicate parameter name: \"" + firstName + "\"");
            }
            
            // add param to the segment defined last
            segments.get(segments.size() - 1).addParam(firstName);
            
            if (moreNames.length > 0) {
                stream(moreNames).forEach(this::param);
            }
            
            return this;
        }
        
        @Override
        public Route.Builder append(String segment) {
            if (!segment.startsWith("/")) {
                throw new IllegalArgumentException("Segment must start with a forward slash.");
            }
            
            if (segment.length() > 1 && segment.endsWith("/")) {
                throw new IllegalArgumentException("Segment must not end with a forward slash character.");
            }
            
            String[] vals = segment.substring(1).split("/");
            assert vals.length > 0;
            
            for (String s : vals) {
                if (s.isEmpty()) {
                    throw new IllegalArgumentException("Empty segment.");
                }
                segments.add(new MutableSegment(s));
            }
            
            return this;
        }
        
        private static final Set<MediaType> SPECIAL = Set.of(NOTHING, NOTHING_AND_ALL, ALL);
        
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
                            NOTHING, NOTHING_AND_ALL, ALL));
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
            return new DefaultRoute(segments, handlers);
        }
    }
    
    private static class MutableSegment implements Segment
    {
        private final String val;
        private final List<String> params;
        
        MutableSegment(String val) {
            this.val = val;
            this.params = new ArrayList<>(0);
        }
        
        void addParam(String param) {
            params.add(param);
        }
        
        @Override
        public boolean isFirst() {
            // TODO: Remove
            throw new UnsupportedOperationException();
        }
        
        @Override
        public String value() {
            return val;
        }
        
        private List<String> unmod;
        
        @Override
        public List<String> params() {
            List<String> u = unmod;
            return u != null ? u : (unmod = unmodifiableList(params));
        }
        
        // TODO: Implement toString
    }
}