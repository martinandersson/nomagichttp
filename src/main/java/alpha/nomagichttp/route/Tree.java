package alpha.nomagichttp.route;

import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static java.lang.Character.MAX_VALUE;
import static java.util.Collections.unmodifiableMap;
import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;

/**
 * A concurrent tree structure.<p>
 * 
 * No node in the tree store its key. Instead, the node's position defines the
 * key which is effectively split into segments and distributed across the
 * branch. All descendants of a node share a common key prefix and the root is
 * the only node whose prefix is the empty key (because it has no parent).<p>
 * 
 * With this class, the client will add-, remove- and lookup entries by
 * traversing the tree, one segment/node at a time. Traversing the tree is done
 * in a <i>reading</i> or <i>writing</i> mode. The API is different for each.<p>
 * 
 * The split has two benefits. Firstly, the api will be more easy to use as it
 * will be trimmed to the intent of the traversing operation. The split also
 * makes it possible for the tree implementation to perform a few optimizations,
 * such as not unnecessarily reserving a node for a reading thread which makes
 * the read operation faster and also doesn't rollback or hinder a concurrently
 * running prune operation on the same branch (the node values are accessed
 * using atomic semantics, so read values are never stale).<p>
 * 
 * TODO: Give example.<p>
 * 
 * Segment keys can never be an empty string or {@code null}. The node value may
 * be {@code null} and if it is, the node will be eligible for being removed
 * from the tree, given it has no descendants carrying a value; also known as
 * "pruning". Pruning operations are transparently done by incoming writing
 * threads after having set a {@code null} value.
 * 
 * @param <V> type of the node's associated value
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class Tree<V>
{
    // Implementation design; see javadoc of Tree.NodeImpl
    
    interface ReadNode<V> {
        /**
         * Returns this node's value, or {@code null} if not present.
         * 
         * @return this node's value, or {@code null} if not present
         */
        V get();
        
        /**
         * Traverse to the next node.
         * 
         * @param segment sub-key
         * @return the next node (or {@code null} if branch doesn't extend further)
         * @throws IllegalArgumentException if {@code segment} is empty
         */
        ReadNode<V> next(String segment);
    }
    
    interface WriteNode<V> {
        /**
         * Returns this node's value, or {@code null} if not present.
         *
         * @return this node's value, or {@code null} if not present
         */
        V get();
        
        /**
         * Set this node's value.
         * 
         * @param v value
         * @return old value
         */
        V set(V v);
        
        /**
         * Set the node's value, if absent (i.e. {@code null}).<p>
         *
         * @param v new value
         * @return old value
         */
        default V setIfAbsent(V v) {
            return setIf(v, Objects::isNull);
        }
        
        /**
         * Set the node's value, if given predicate returns {@code true} for the
         * old value.<p>
         * 
         * @param v new value
         * @param test old value predicate
         * @return old value
         */
        V setIf(V v, Predicate<? super V> test);
        
        /**
         * Returns {@code true} if this node has no children, otherwise {@code
         * false}.
         * 
         * @return {@code true} if this node has no children,
         *         otherwise {@code false}
         * 
         * @see #hasChildrenKeyedByPrefix(String)
         */
        default boolean hasNoChildren() {
            return !hasChildrenKeyedByPrefix("");
        }
        
        /**
         * Returns {@code true} if this node has a child keyed by the given
         * segment, otherwise {@code false}.
         * 
         * @return {@code true} if this node has a child keyed by the given
         *          * segment, otherwise {@code false}
         * 
         * @see #hasChildrenKeyedByPrefix(String)
         * 
         * @throws NullPointerException if {@code segment} is {@code null}
         */
        boolean hasChild(String segment);
        
        /**
         * Returns the child keyed by the given segment.<p>
         * 
         * This method is intended only to be used as a method to check if a
         * child exists, potentially failing the write operation if it do - at
         * the discretion of the client, but with access to said node for
         * inclusion in a potential error message. Traversing the tree further
         * must always be done using one of the {@code
         * WriteNode.nextOrCreate**()} methods. Do not invoke {@link
         * ReadNode#next(String)} on the returned object.<p>
         * 
         * Technically, the behavior of this method is different from {@code
         * ReadNode.next()} in that it will prune the tree first (to give a more
         * accurate answer whether or not the child exist). The behavior is also
         * different from {@code WriteNode.nextOrCreate**()} in that this method
         * will not reserve the returned node, e.g. block concurrent prune
         * operations from running. Again, the intent is to potentially fail the
         * write operation if this method returns a non-null value, not to
         * traverse the tree any further.<p>
         * 
         * @param segment of child
         * 
         * @return child node, or {@code null} if it does not exist
         * 
         * @throws NullPointerException if {@code segment} is {@code null}
         */
        ReadNode<V> getChild(String segment);
        
        /**
         * Returns {@code true} if there's any first-level children of this node
         * whose key starts with the given prefix.<p>
         * 
         * This method is useful from inside a condition argument passed to
         * {@link #nextOrCreateIf(String, BooleanSupplier)} when
         * algorithmically enforcing a set of variants of children. For example,
         * perhaps the next tree node should not be created if there's already a
         * certain range of other nodes in there.
         * 
         * @param prefix of key segment
         * 
         * @return {@code true} if this node has any children whose key starts
         *         with the given prefix, otherwise {@code false}
         * 
         * @throws NullPointerException if {@code prefix} is {@code null}
         */
        boolean hasChildrenKeyedByPrefix(String prefix);
        
        /**
         * Traverse the tree to a child node, creating the child if it does not
         * exist.
         * 
         * @param segment value
         * 
         * @return the child node (never {@code null})
         * 
         * @throws NullPointerException
         *             if {@code segment} is {@code null}
         * 
         * @throws IllegalArgumentException
         *             if {@code segment} is the empty string
         */
        default WriteNode<V> nextOrCreate(String segment) {
            return nextOrCreateIf(segment, () -> true);
        }
        
        /**
         * Possibly traverse the tree to a child node, creating the child if it
         * does not exist and the given {@code condition} returns {@code true}.
         *
         * @param segment value
         * @param condition whether or not to create a non-existent child
         * 
         * @return the child node ({@code null} if child does not exist and
         *         condition banned the child from being created)
         * 
         * @throws NullPointerException
         *             if {@code segment} or {@code condition} is {@code null}
         * 
         * @throws IllegalArgumentException
         *             if {@code segment} is the empty string
         */
        WriteNode<V> nextOrCreateIf(String segment, BooleanSupplier condition);
    }
    
    private final NodeImpl root;
    
    /** Is {@code true} if current writing thread needs to prune the tree. */
    private final ThreadLocal<Boolean> clean;
    
    /** Is {@code true} while tree is being pruned (any other thread asking to start need not bother). */
    private final AtomicBoolean cleaning;
    
    /** Writer thread walking the tree reserves its path, unreleasing before return. */
    private final ThreadLocal<Deque<NodeImpl>> release;
    
    Tree() {
        root     = new NodeImpl(null);
        clean    = ThreadLocal.withInitial(() -> false);
        cleaning = new AtomicBoolean(false);
        release  = ThreadLocal.withInitial(ArrayDeque::new);
    }
    
    /**
     * Traverse the tree in read mode.
     * 
     * @return the root (never {@code null})
     */
    ReadNode<V> read() {
        return root;
    }
    
    /**
     * Traverse the tree in write mode.<p>
     * 
     * The first invocation of the given {@code digger} receives the tree's root
     * node as argument, then, the digger will be re-executed with whichever
     * node it returned previously until the digger decides that the operation
     * is done and returns null.<p>
     * 
     * The digger is free to dig only one or many levels at a time.<p>
     * 
     * Node values may be arbitrary set along the path, at the discretion of the
     * client. However, setting {@code null} along the path and then continue
     * digging may yield worse performance versus using multiple walk
     * operations. For bulk operations, it's recommended to walk the tree
     * repeatedly for each node targeted.<p>
     * 
     * The tree implementation does not synchronize on the monitor of nodes.
     * The nodes may be used as mutually exclusive locks at the discretion of
     * the client.
     * 
     * @param digger function which returns the next node to traverse
     */
    void write(UnaryOperator<WriteNode<V>> digger) {
        try {
            for (WriteNode<V> n = root; n != null; n = digger.apply(n))
                ; // Empty
        } finally {
            Deque<NodeImpl> visited = release.get();
            while (!visited.isEmpty()) {
                visited.pollLast().unreserve();
            }
        }
    }
    
    V setIfAbsent(Iterable<String> keySegments, V v) {
        final Iterator<String> it = keySegments.iterator();
        if (!it.hasNext()) {
            return root.setIfAbsent(v);
        } else {
            Object[] old = new Object[1];
            write(n -> {
                final String s;
                try {
                    s = it.next();
                } catch (NoSuchElementException e) {
                    // Node found, set val and return
                    old[0] = n.setIfAbsent(v);
                    return null;
                }
                return n.nextOrCreate(s);
            });
            tryPruningTree();
            @SuppressWarnings("unchecked")
            V o = (V) old[0];
            return o;
        }
    }
    
    V clear(Iterable<String> keySegments) {
        return clearIf(keySegments, ignored -> true);
    }
    
    V clearIf(Iterable<String> keySegments, Predicate<V> test) {
        final Iterator<String> it = keySegments.iterator();
        
        // This is cheating as we use read mode to eventually set null,
        // however, we don't need to reserve the nodes since we won't set a
        // value we can not afford to orphan/make unreachable
        ReadNode<V> n = root;
        while (it.hasNext())  {
            n = n.next(it.next());
            if (n == null) {
                // Branch doesn't extend further, job done
                return null;
            }
        }
        
        V v = ((NodeImpl) n).setIf(null, test);
        if (v != null) {
            tryPruningTree();
        }
        return v;
    }
    
    private void tryPruningTree() {
        // Clean tree only if branch was flagged dirty and no cleanup job is already running
        if (!clean.get()) {
            return;
        }
        clean.set(false);
        if (cleaning.compareAndSet(false, true)) {
            try {
                root.prune();
            } finally {
                cleaning.set(false);
            }
        }
    }
    
    /**
     * FOR TESTS ONLY: Find the given node in the tree and compute its assembled
     * key.
     * 
     * @param delimiter used to join all key segments
     * @param n target node
     * 
     * @return the node's final key
     * 
     * @throws NoSuchElementException if node can not be found
     */
    String extractKey(CharSequence delimiter, ReadNode<?> n) {
        return root.entryStreamGraph("", delimiter, "", identity())
                .filter(e -> e.getValue().equals(n))
                .map(Map.Entry::getKey)
                .findFirst().get();
    }
    
    /**
     * FOR TESTS ONLY: Flatten the tree and dump all node values.<p>
     * 
     * Note that for as long as a particular branch has not been pruned, a map
     * entry - or the entire branch for that matter - may map to a {@code null}
     * value.
     * 
     * @param delimiter used to join all key segments
     * 
     * @return a map of the tree
     */
    Map<String, V> toMap(CharSequence delimiter) {
        // https://bugs.openjdk.java.net/browse/JDK-8148463
        SortedMap<String, V> m = new TreeMap<>();
        root.entryStreamGraph("", delimiter, "", ReadNode::get).forEach(e -> {
            if (m.putIfAbsent(e.getKey(), e.getValue()) != null) {
                throw new AssertionError("Duplicated path/key: " + e.getKey());
            }
        });
        return unmodifiableMap(m);
    }
    
    /**
     * A node is associated with an arbitrary value and may have descendant
     * children nodes keyed by a string. Each child may in turn contain
     * children, effectively making up a tree.<p>
     * 
     * A tree is built upon demand when being traversed using the method {@link
     * #nextOrCreate(String)}. Each node returned from this method is
     * immediately {@link #reserve() reserved} until the enclosing operation has
     * completed at which point all the reserved nodes must be
     * {@link #unreserve() unreserved} (ideally in reversed order). This will
     * ensure that a concurrently running {@link #prune() pruning} operation
     * does not delete the link to a reserved node which would otherwise have
     * made the node (and its value) unreachable.<p>
     * 
     * Reserving a node and setting its value generally do not block and if it
     * does the block is expected to be minuscule. Creating a child node as well
     * as pruning is subject to synchronized monitors as used internally by a
     * {@code ConcurrentMap} sub-component. Similarly, these monitors are not
     * expected to be contended or to be held for very long.<p>
     * 
     * Most importantly, traversing the tree uses no <i>mutually exclusive</i>
     * locks imposed by this class and does not generally block (at the
     * discretion of the {@code ConcurrentMap} sub-component).
     * 
     * @author Martin Andersson (webmaster at martinandersson.com)
     */
    private class NodeImpl implements ReadNode<V>, WriteNode<V>
    {
        private final AtomicReference<V> v;
        // Does not allow null to be used as key or value
        private final ConcurrentNavigableMap<String, NodeImpl> kids;
        // Used only for accessing the orphan flag
        private final Lock read, write;
        private boolean orphan;
        
        NodeImpl(V v) {
            this.v = new AtomicReference<>(v);
            this.kids = new ConcurrentSkipListMap<>();
            
            ReentrantReadWriteLock l = new ReentrantReadWriteLock();
            read = l.readLock();
            write = l.writeLock();
            
            orphan = false;
        }
        
        // Read interface
        // ----
        
        @Override
        public V get() {
            return v.get();
        }
        
        @Override
        public NodeImpl next(String segment) {
            return kids.get(requireNotNullOrEmpty(segment));
        }
        
        // Write interface
        // ----
        
        @Override
        public V set(V v) {
            V o = this.v.getAndSet(v);
            if (o != null && v == null) {
                clean.set(true);
            }
            return o;
        }
        
        @Override
        public V setIf(V v, Predicate<? super V> test) {
            V o1 = this.v.getAndUpdate(o2 -> test.test(o2) ? v : o2);
            if (o1 != null && v == null) {
                clean.set(true);
            }
            return o1;
        }
        
        @Override
        public boolean hasChild(String segment) {
            prune();
            return kids.containsKey(segment);
        }
        
        @Override
        public ReadNode<V> getChild(String segment) {
            prune();
            return kids.get(segment);
        }
        
        @Override
        public boolean hasChildrenKeyedByPrefix(String prefix) {
            prune();
            return !subMapFrom(kids, prefix).isEmpty();
        }
        
        @Override
        public NodeImpl nextOrCreateIf(String segment, BooleanSupplier condition) {
            requireNotNullOrEmpty(segment);
            requireNonNull(condition);
            
            NodeImpl c;
            for (;;) {
                c = kids.computeIfAbsent(segment, keyIgnored ->
                        condition.getAsBoolean() ? new NodeImpl(null) : null);
                
                if (c == null) {
                    return null;
                }
                
                try {
                    c.reserve();
                    release.get().add(c);
                    break;
                } catch (StaleBranchException e) {
                    // Retry
                }
            }
            
            return c;
        }
        
        /**
         * Reserve this node.<p>
         * 
         * Reservation excludes the node from being touched by a concurrently
         * running {@link #prune() pruning} operation but does not block or
         * stop other concurrently running operations from attempting to set the
         * node's value or create children.<p>
         * 
         * The root should never be reserved as reserving the root node could
         * throw a [for the root] non-applicable {@code StaleBranchException}.
         * 
         * @throws StaleBranchException
         *             if a pruning operation has removed the link to this node
         */
        private void reserve() throws StaleBranchException {
            assert this != root;
            read.lock();
            if (orphan) {
                read.unlock();
                throw new StaleBranchException();
            }
        }
        
        void unreserve() {
            assert this != root;
            read.unlock();
        }
        
        // API for enclosing tree
        // ----
        
        /**
         * Will delete the link to all children nodes that has no associated
         * value and who themselves have no children. The algorithm works
         * recursively depth-first, returning {@code true} if this node has no
         * value and no children.<p>
         * 
         * This is semantically equivalent to pruning the branch of a tree (or
         * the entire tree if pruning starts at the root) by removing
         * superfluous nodes that serve no purpose other than to take up memory.
         * In fact, pruning must take place; without it we would risk having a
         * memory leak.<p>
         * 
         * It is imperative that a branch-expanding operation also {@link
         * #reserve()} each node that it creates. See {@link NodeImpl}.
         * 
         * @return {@code true} if this node is an orphan after having
         *         recursively pruned all children depth-first
         */
        boolean prune() {
            kids.values().removeIf(NodeImpl::prune);
            
            if (!write.tryLock()) {
                return false;
            }
            
            try {
                orphan = kids.isEmpty() && v.get() == null;
                return orphan;
            } finally {
                write.unlock();
            }
        }
        
        Stream<NodeImpl> entryStreamFlat(String segment, String... moreSegments) {
            var b = nextAccept(segment, Stream::builder, null);
            for (String s : moreSegments) {
                b = nextAccept(s, Stream::builder, b);
            }
            return b == null ? Stream.empty() : b.build();
        }
        
        private <T extends Consumer<? super NodeImpl>> T nextAccept(
                String segment,
                Supplier<T> supplier,
                T consumer)
        {
            NodeImpl n = next(segment);
            if (n != null) {
                if (consumer == null) {
                    consumer = supplier.get();
                }
                consumer.accept(n);
            }
            return consumer;
        }
        
        <R> Stream<Map.Entry<String, R>> entryStreamGraph(
                String prefix,
                CharSequence delimiter,
                String key,
                Function<NodeImpl, ? extends R> mapper)
        {
            final String path = prefix + delimiter + key;
            
            Stream<Map.Entry<String, R>>
                    self = Stream.of(entry(path, mapper.apply(this))),
                    kids = this.kids.entrySet().stream().flatMap(e ->
                            e.getValue().entryStreamGraph(key.isEmpty() ? "" : path, delimiter, e.getKey(), mapper));
            
            return Stream.concat(self, kids);
        }
        
        @Override
        public String toString() {
            return NodeImpl.class.getName() +
                    "{v=" + get() + ", children.size()=" + kids.size() + "}";
        }
    }
    
    private static String requireNotNullOrEmpty(String segment) {
        if (segment.isEmpty()) {
            throw new IllegalArgumentException("Segment value is empty.");
        }
        return segment;
    }
    
    /**
     * The node has been made orphaned by a pruning operation and is no longer
     * part of an active branch. Code catching this exception should re-acquire
     * or re-create the node upon which it attempted to operate.
     */
    private static class StaleBranchException extends Exception {
        private static final long serialVersionUID = 1L;
        // Empty
    }
    
    /**
     * Returns a {@code Map.Entry} that is immutable and allow null values,
     * 
     * @param k key
     * @param v value
     * @param <K> type of key
     * @param <V> type of value
     * 
     * @return a map entry
     */
    static <K, V> Map.Entry<K, V> entry(K k, V v) {
        return new AbstractMap.SimpleImmutableEntry<>(k, v);
    }
    
    // https://stackoverflow.com/a/65825999/1268003
    private static <V> NavigableMap<String, V> subMapFrom(
            NavigableMap<String, V> map, String keyPrefix)
    {
        final String fromKey = keyPrefix, toKey; // undefined
        
        // Alias
        String p = keyPrefix;
        
        if (p.isEmpty()) {
            // No need for a sub map
            return map;
        }
        
        // ("ab" + MAX_VALUE + MAX_VALUE + ...) returns index 1
        final int i = lastIndexOfNonMaxChar(p);
        
        if (i == -1) {
            // Prefix is all MAX_VALUE through and through, so grab rest of map
            return map.tailMap(p, true);
        }
        
        if (i < p.length() - 1) {
            // Target char for bumping is not last char; cut out the residue
            // ("ab" + MAX_VALUE + MAX_VALUE + ...) becomes "ab"
            p = p.substring(0, i + 1);
        }
        toKey = bumpChar(p, i);
        
        return map.subMap(fromKey, true, toKey, false);
    }
    
    private static int lastIndexOfNonMaxChar(String str) {
        int i = str.length();
        
        // Walk backwards, while we have a valid index
        while (--i >= 0) {
            if (str.charAt(i) < MAX_VALUE) {
                return i;
            }
        }
        
        return -1;
    }
    
    private static String bumpChar(String str, int pos) {
        assert !str.isEmpty();
        assert pos >= 0 && pos < str.length();
        
        final char c = str.charAt(pos);
        assert c < MAX_VALUE;
        
        StringBuilder b = new StringBuilder(str);
        b.setCharAt(pos, (char) (c + 1));
        return b.toString();
    }
}