package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.action.ActionNonUniqueException;
import alpha.nomagichttp.action.ActionPatternInvalidException;
import alpha.nomagichttp.action.ActionRegistry;
import alpha.nomagichttp.action.AfterAction;
import alpha.nomagichttp.action.BeforeAction;
import alpha.nomagichttp.route.SegmentsBuilder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.RandomAccess;
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
import static java.lang.Integer.compare;
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
        var seg = toSegments(pattern);
        @SuppressWarnings("varargs")
        var s = stream(action, more).map(a -> wrapperFactory.apply(a, seg));
        s.forEach(wrapper -> add(tree, wrapper));
    }
    
    private static Iterable<String> toSegments(String pattern) {
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
    
    private static <W extends AbstractWrapper<?>> void add(Tree<Set<W>> tree, W wrapped) {
        final Iterator<String> it = wrapped.segments().iterator();
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
                if (!set.add(wrapped)) {
                    throw new ActionNonUniqueException("Already added: " + wrapped.action());
                }
                // job done
                return null;
            }
        });
    }
    
    /**
     * A match of an action from the registry.
     * 
     * @param <A> the action
     */
    interface Match<A> {
        /**
         * Returns the matched action.
         * 
         * @return the matched action
         */
        A action();
        
        /**
         * Returns the segments (pattern) used to register the action with.
         * 
         * @return the segments (pattern) used to register the action with.
         */
        Iterable<String> segments();
    }
    
    /**
     * Lookup before-actions.<p>
     * 
     * If the returned list is not empty, then it also implements {@link
     * RandomAccess} and is mutable.
     * 
     * @param rt request target
     * @return matches (never {@code null})
     */
    List<Match<BeforeAction>> lookupBefore(SkeletonRequestTarget rt) {
        return lookup(before, rt);
    }
    
    /**
     * Lookup after-actions.<p>
     * 
     * If the returned list is not empty, then it also implements {@link
     * RandomAccess} and is mutable.
     * 
     * @param rt request target
     * @return matches (never {@code null})
     */
    List<Match<AfterAction>> lookupAfter(SkeletonRequestTarget rt) {
        return lookup(after, rt);
    }
    
    private static <W extends AbstractWrapper<A>, A> List<Match<A>> lookup(
            Tree<Set<W>> tree, SkeletonRequestTarget rt)
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
    
    // We're reusing buffers only to minimize garbage. This also assumes that a
    // thread will never run through the lookup operation recursively. Lookups
    // are only performed once during HTTP exchange initialization.
    // TODO: This is premature optimization. Need proof it is better.
    //       Also see https://stackoverflow.com/a/18371813
    private static final ThreadLocal<List<?>>  BUF1 = withInitial(() -> new ArrayList<>(INITIAL_CAPACITY));
    private static final ThreadLocal<Deque<?>> BUF2 = withInitial(() -> new ArrayDeque<>(INITIAL_CAPACITY));
    private static final ThreadLocal<List<?>>  BUF3 = withInitial(() -> new ArrayList<>(INITIAL_CAPACITY));
    
    private static <T> T get(ThreadLocal<?> buff) {
        @SuppressWarnings("unchecked")
        T t = (T) buff.get();
        return t;
    }
    
    private static <W extends AbstractWrapper<A>, A> List<Match<A>> lookup(
            Tree<Set<W>> tree, SkeletonRequestTarget rt,
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
        
        trails.add(tree.read());
        
        // TODO: Would like to use RandomAccess
        for (String seg : rt.segments()) {
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
            // but first, add leaves' catch-all
            n.nextValueIfPresent(ASTERISK_STR, matches::addAll);
            n.ifPresent(matches::addAll);
        });
        
        return matches.isEmpty() ? List.of() :
               matches.stream()
                      .sorted()
                      .collect(toCollection(() ->
                              new ArrayList<>(matches.size())));
    }
    
    private static final class WrappedBeforeAction
            extends AbstractWrapper<BeforeAction>
            implements Comparable<WrappedBeforeAction>
    {
        WrappedBeforeAction(BeforeAction action, Iterable<String> segments) {
            super(action, segments);
        }
        
        @Override
        public int compareTo(WrappedBeforeAction other) {
            int x = this.compressedPath().compareTo(other.compressedPath());
            return x != 0 ? x : compare(insertionOrder(), other.insertionOrder());
        }
    }
    
    private static final class WrappedAfterAction
            extends AbstractWrapper<AfterAction>
            implements Comparable<WrappedAfterAction>
    {
        WrappedAfterAction(AfterAction action, Iterable<String> segments) {
            super(action, segments);
        }
        
        @Override
        public int compareTo(WrappedAfterAction other) {
            int x = this.compressedPath().compareTo(other.compressedPath());
            // NEG X
            return x != 0 ? -x : compare(insertionOrder(), other.insertionOrder());
        }
    }
    
    /**
     * A wrapper of an action.<p>
     * 
     * This class computes a {@link #compressedPath()} of the action's segments.
     * The root (empty segments) will be normalized to "-". Other segments will
     * be stripped of their parameter names (not necessarily for correctness,
     * only for performance). This leaves a harmonized path suitable for
     * stupidly fast sorting. "*" (catch-all) comes before "-" (root), which
     * comes before ":" (single), which comes before "segment". For any inbound
     * request path and segment, no different static segments will ever be
     * compared. If the path is equal, the concrete wrapper class ought to
     * compare the insertion order next, retrievable using
     * {@link #insertionOrder()}.<p>
     * 
     * The hashcode and equals implementation delegates directly to the action
     * object.
     * 
     * @param <A> type of action
     */
    private static abstract class AbstractWrapper<A> implements Match<A> {
        private static final AtomicInteger SEQ = new AtomicInteger();
        private final A action;
        private final Iterable<String> segments;
        private final String cp;
        private final int order;
        
        AbstractWrapper(A action, Iterable<String> segments) {
            this.action   = requireNonNull(action);
            this.segments = segments;
            this.order    = SEQ.getAndIncrement();
            var cp = String.join("/", noParamNames(segments));
            this.cp = cp.isEmpty() ? "-" : cp;
        }
        
        @Override
        public final A action() {
            return action;
        }
        
        @Override
        public final Iterable<String> segments() {
            return segments;
        }
        
        protected final String compressedPath() {
            return cp;
        }
        
        protected final int insertionOrder() {
            return order;
        }
        
        @Override
        public final String toString() {
            return AbstractWrapper.class.getSimpleName() +
                    "{action=" + action() + ", segments=" + segments() + "}";
        }
        
        @Override
        public final int hashCode() {
            return action.hashCode();
        }
        
        @Override
        public final boolean equals(Object obj) {
            var other = (AbstractWrapper<?>) obj;
            return action.equals(other.action());
        }
    }
}