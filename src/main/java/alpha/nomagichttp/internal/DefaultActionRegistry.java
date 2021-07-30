package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.action.ActionNonUniqueException;
import alpha.nomagichttp.action.ActionPatternInvalidException;
import alpha.nomagichttp.action.ActionRegistry;
import alpha.nomagichttp.action.AfterAction;
import alpha.nomagichttp.action.BeforeAction;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.route.SegmentsBuilder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static alpha.nomagichttp.internal.Segments.ASTERISK_CH;
import static alpha.nomagichttp.internal.Segments.ASTERISK_STR;
import static alpha.nomagichttp.internal.Segments.COLON_CH;
import static alpha.nomagichttp.internal.Segments.COLON_STR;
import static alpha.nomagichttp.internal.Segments.noParamNames;
import static alpha.nomagichttp.util.Arrays.stream;
import static java.lang.ThreadLocal.withInitial;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toCollection;

/**
 * Default implementation of {@link ActionRegistry}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class DefaultActionRegistry implements ActionRegistry
{
    private static final int INITIAL_CAPACITY = 3;
    
    private final Tree<Set<WrappedBeforeAction>> before;
    private final Tree<Set<WrappedAfterAction>> after;
    private final HttpServer server;
    
    DefaultActionRegistry(HttpServer server) {
        this.before = new Tree<>();
        this.after  = new Tree<>();
        this.server = server;
    }
    
    @Override
    public HttpServer before(String pattern, BeforeAction first, BeforeAction... more) {
        addAll(pattern, before, WrappedBeforeAction::new, first, more);
        return server;
    }
    
    @Override
    public HttpServer after(String pattern, AfterAction first, AfterAction... more) {
        addAll(pattern, after, WrappedAfterAction::new, first, more);
        return server;
    }
    
    @SafeVarargs
    private static <W extends AbstractWrapper<A>, A> void addAll(
            String pattern, Tree<Set<W>> tree,
            BiFunction<? super A, Iterable<String>, W> wrapperFactory,
            A action, A... more)
    {
        var segments = segments(pattern);
        @SuppressWarnings("varargs")
        var s = stream(action, more).map(a -> wrapperFactory.apply(a, segments));
        s.forEach(wrapper -> add(tree, wrapper));
    }
    
    private static Iterable<String> segments(String pattern) {
        var b = new SegmentsBuilder();
        if (!pattern.equals("/")) {
            try {
                b = b.append(pattern);
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new ActionPatternInvalidException(e);
            }
        }
        return b.asIterable();
    }
    
    private static <W extends AbstractWrapper<?>> void add(Tree<Set<W>> tree, W action) {
        final Iterator<String> it = action.segments().iterator();
        tree.write(n -> {
            if (it.hasNext()) {
                // dig deeper
                final String s = it.next();
                switch (s.charAt(0)) {
                    // TODO: Technically, single path param and catch-all are not
                    //  mutually exclusive, and so they could go into the same set?
                    case COLON_CH:
                        return n.nextOrCreate(COLON_STR);
                    case ASTERISK_CH:
                        assert !it.hasNext();
                        return n.nextOrCreate(ASTERISK_STR);
                    default:
                        return n.nextOrCreate(s);
                }
            } else {
                // We're at target node, store
                var set = n.setIfAbsent(CopyOnWriteArraySet::new);
                if (!set.add(action)) {
                    throw new ActionNonUniqueException("Already added: " + action.get());
                }
                // job done
                return null;
            }
        });
    }
    
    /**
     * A match of an action.
     */
    // TODO: Share with DefaultRouteRegistry
    interface Match<A>
    {
        /**
         * Returns the matched action.
         * 
         * @return the matched action (never {@code null})
         */
        A action();
        
        /**
         * Equivalent to {@link Request.Parameters#path(String)}.
         * 
         * @param name of path parameter (case sensitive)
         * @return the path parameter value (percent-decoded)
         */
        String pathParam(String name);
        
        /**
         * Equivalent to {@link Request.Parameters#pathRaw(String)}.
         * 
         * @param name of path parameter (case sensitive)
         * @return the raw path parameter value (not decoded/unescaped)
         */
        String pathParamRaw(String name);
    }
    
    List<Match<BeforeAction>> lookupBefore(RequestTarget rt) {
        return lookup(before, rt);
    }
    
    List<Match<AfterAction>> lookupAfter(RequestTarget rt) {
        return lookup(after, rt);
    }
    
    private static <W extends AbstractWrapper<A>, A> List<Match<A>> lookup(
            Tree<Set<W>> tree, RequestTarget rt)
    {
        List<W>                      buf1 = get(BUF1);
        Deque<Tree.ReadNode<Set<W>>> buf2 = get(BUF2);
        List<Tree.ReadNode<Set<W>>>  buf3 = get(BUF3);
        try {
            return lookup(tree, rt, buf1, buf2, buf3);
        } finally {
            buf1.clear();
            buf2.clear();
            buf3.clear();
        }
    }
    
    private static final ThreadLocal<List<?>>  BUF1 = withInitial(() -> new ArrayList<>(INITIAL_CAPACITY));
    private static final ThreadLocal<Deque<?>> BUF2 = withInitial(() -> new ArrayDeque<>(INITIAL_CAPACITY));
    private static final ThreadLocal<List<?>>  BUF3 = withInitial(() -> new ArrayList<>(INITIAL_CAPACITY));
    
    private static <T> T get(ThreadLocal<?> buff) {
        @SuppressWarnings("unchecked")
        T t = (T) buff.get();
        return t;
    }
    
    private static <W extends AbstractWrapper<A>, A> List<Match<A>> lookup(
            Tree<Set<W>> tree, RequestTarget rt,
            List<W> matches,
            Deque<Tree.ReadNode<Set<W>>> trails,
            List<Tree.ReadNode<Set<W>>> batch)
    {
        // DefaultRouteRegistry has a pretty simple job; poll one segment at a
        // time while walking the tree one level at a time. Only one route can
        // match, after all. Here, me must investigate any number of active
        // branches.
        //    For each segment, we iterate <trails> (current leaf nodes) and
        // immediately record <catchAll> matches and save discovered children
        // (single + static) into <batch>, which will become the new <trails> to
        // check against the next segment. After all segments have been
        // iterated, the residue in <trails> are the furthermost leaves which
        // represents matched nodes.
        //    Post-processing merges <catchAll> and <trails>, then map each to
        // the result container and sort.
        
        trails.add(tree.read());
        
        // TODO: Would like to use RandomAccess
        for (String seg : rt.segmentsPercentDecoded()) {
            if (trails.isEmpty()) {
                // All segments must match
                break;
            }
            
            Tree.ReadNode<Set<W>> n;
            while ((n = trails.poll()) != null) {
                // Catch-all child node is always a match
                n.nextValueIfPresent(ASTERISK_STR, matches::addAll);
                // Keep digging the trails
                n.nextIfPresent(COLON_STR, batch::add);
                n.nextIfPresent(seg, batch::add);
            }
            
            if (trails.addAll(batch)) {
                // clear batch because we'll go again, otherwise call site clears
                // (and so wrapping this in an if-clause is premature optimization)
                // (could optimize even more lol, e.g. not clear if we're on the last segment)
                batch.clear();
            }
        }
        
        // anything left is a match
        trails.forEach(n -> {
            // but first, add leaves catch-all
            n.nextValueIfPresent(ASTERISK_STR, matches::addAll);
            n.ifPresent(matches::addAll);
        });
        
        return matches.isEmpty() ? List.of() :
               matches.stream()
                      .sorted()
                      .map(w -> DefaultMatch.of(w, rt))
                      .collect(toCollection(() ->
                              new ArrayList<>(matches.size())));
    }
    
    private static final class WrappedBeforeAction
            extends AbstractWrapper<BeforeAction>
            implements Comparable<WrappedBeforeAction>
    {
        private final String path;
        
        WrappedBeforeAction(BeforeAction action, Iterable<String> segments) {
            super(action, segments);
            var p = String.join("/", noParamNames(segments));
            path = p.isEmpty() ? "-" : p;
        }
        
        @Override
        public int compareTo(WrappedBeforeAction other) {
            int x = this.path.compareTo(other.path);
            return x != 0 ? x : Integer.compare(insertionOrder(), other.insertionOrder());
        }
    }
    
    private static final class WrappedAfterAction extends AbstractWrapper<AfterAction> implements Comparable<WrappedAfterAction> {
        private final String path;
        
        WrappedAfterAction(AfterAction action, Iterable<String> segments) {
            super(action, segments);
            var p = String.join("/", noParamNames(segments));
            path = p.isEmpty() ? "-" : p;
        }
        
        @Override
        public int compareTo(WrappedAfterAction other) {
            int x = this.path.compareTo(other.path);
            // NEG X
            return x != 0 ? -x : Integer.compare(insertionOrder(), other.insertionOrder());
        }
    }
    
    private static abstract class AbstractWrapper<A> {
        private static final AtomicInteger SEQ = new AtomicInteger();
        private final A action;
        private final Iterable<String> segments;
        private final int order;
        
        AbstractWrapper(A action, Iterable<String> segments) {
            this.action   = requireNonNull(action);
            this.segments = segments;
            this.order    = SEQ.getAndIncrement();
        }
        
        A get() {
            return action;
        }
        
        Iterable<String> segments() {
            return segments;
        }
        
        int insertionOrder() {
            return order;
        }
        
        @Override
        public String toString() {
            return AbstractWrapper.class.getSimpleName() +
                    "{action=" + get() + ", segments=" + segments() + "}";
        }
        
        @Override
        public int hashCode() {
            return action.hashCode();
        }
        
        @Override
        public boolean equals(Object obj) {
            var other = (AbstractWrapper<?>) obj;
            return action.equals(other.get());
        }
    }
    
    // TODO: copy paste from DefaultRouteReg, DRY
    // Package-private only for tests
    static class DefaultMatch<A> implements Match<A>
    {
        private final A action;
        private final Map<String, String> paramsRaw, paramsDec;
        
        // TODO: Make path params lazy
        static <W extends AbstractWrapper<A>, A> DefaultMatch<A>
                of(W actionWrapper, RequestTarget rt)
        {
            // We need to map "request/path/segments" to "resource/:path/*parameters"
            Iterator<String>    decIt  = rt.segmentsPercentDecoded().iterator(),
                                segIt  = actionWrapper.segments().iterator();
            Map<String, String> rawMap = Map.of(),
                                decMap = Map.of();
            
            String catchAllKey = null;
            
            for (String r : rt.segmentsNotPercentDecoded()) {
                String d = decIt.next();
                
                if (catchAllKey == null) {
                    // Catch-all not activated, consume next resource segment
                    String s = segIt.next();
                    
                    switch (s.charAt(0)) {
                        case COLON_CH:
                            // Single path param goes to map
                            String k = s.substring(1),
                                    o = (rawMap = mk(rawMap)).put(k, r);
                            assert o == null;
                            o = (decMap = mk(decMap)).put(k, d);
                            assert o == null;
                            break;
                        case ASTERISK_CH:
                            // Toggle catch-all phase with this segment as seed
                            catchAllKey = s.substring(1);
                            (rawMap = mk(rawMap)).put(catchAllKey, '/' + r);
                            (decMap = mk(decMap)).put(catchAllKey, '/' + d);
                            break;
                        default:
                            // Static segments we're not interested in
                            break;
                    }
                } else {
                    // Consume all remaining request segments as catch-all
                    rawMap.merge(catchAllKey, '/' + r, String::concat);
                    decMap.merge(catchAllKey, '/' + d, String::concat);
                }
            }
            
            // We're done with the request path, but resource may still have a catch-all segment in there
            if (segIt.hasNext()) {
                String s = segIt.next();
                assert s.startsWith("*");
                assert !segIt.hasNext();
                assert catchAllKey == null;
                catchAllKey = s.substring(1);
            }
            
            // We could have toggled to catch-all, but no path segment was consumed for it, and
            if (catchAllKey != null && !rawMap.containsKey(catchAllKey)) {
                // route JavaDoc promises to default with a '/'
                (rawMap = mk(rawMap)).put(catchAllKey, "/");
                (decMap = mk(decMap)).put(catchAllKey, "/");
            }
            
            assert !decIt.hasNext();
            return new DefaultMatch<>(actionWrapper.get(), rawMap, decMap);
        }
        
        private static <K, V> Map<K, V> mk(Map<K, V> map) {
            return map.isEmpty() ? new HashMap<>() : map;
        }
        
        // Package-private for tests
        DefaultMatch(A action, Map<String, String> paramsRaw, Map<String, String> paramsDec) {
            this.action = action;
            this.paramsRaw = paramsRaw;
            this.paramsDec = paramsDec;
        }
        
        @Override
        public A action() {
            return action;
        }
        
        @Override
        public String pathParam(String name) {
            return paramsDec.get(name);
        }
        
        @Override
        public String pathParamRaw(String name) {
            return paramsRaw.get(name);
        }
        
        @Override
        public String toString() {
            return DefaultMatch.class.getSimpleName() +  "{" +
                    "action=" + action + ", " +
                    "paramsRaw=" + paramsRaw + ", " +
                    "paramsDec=" + paramsDec + '}';
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(action, paramsRaw, paramsDec);
        }
        
        @Override
        public boolean equals(Object obj) {
            requireNonNull(obj);
            assert obj instanceof DefaultMatch;
            @SuppressWarnings("unchecked")
            var that = (DefaultMatch<A>) obj;
            return Objects.equals(this.action, that.action) &&
                   Objects.equals(this.paramsRaw, that.paramsRaw) &&
                   Objects.equals(this.paramsDec, that.paramsDec);
        }
    }
}