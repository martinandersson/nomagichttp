package alpha.nomagichttp.internal;

import alpha.nomagichttp.util.SeriallyRunnable;

import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/**
 * A tree structure that expand branches on demand. No node in the tree store
 * its key. Instead, the node's position defines the key which is effectively
 * distributed across the branch. All descendants of a node share a common key
 * prefix and the root is the only node associated with an empty key.
 * 
 * @param <V> type of the node's associated value
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class Tree<V>
{
    private static final Deque<?> EMPTY = new ArrayDeque<>(0);
    
    private static <V> Deque<Node<V>> empty() {
        @SuppressWarnings("unchecked")
        Deque<Node<V>> typed = (Deque<Node<V>>) EMPTY;
        return typed;
    }
    
    private final Node<V> root;
    private final SeriallyRunnable cleanup;
    
    Tree() {
        root = new Node<>(null);
        cleanup = new SeriallyRunnable(root::prune);
    }
    
    void setIfAbsent(Iterable<String> keySegments, V v) {
        setIfAbsent(keySegments, v, null);
    }
    
    void setIfAbsent(Iterable<String> keySegments, V v, Consumer<V> otherwise) {
        final Iterator<String> it = keySegments.iterator();
        final Deque<Node<V>> release = it.hasNext() ? new ArrayDeque<>() : empty();
        
        Node<V> n = root;
        while (it.hasNext())  {
            final String s = it.next();
            Node<V> c;
            for (;;) {
                c = n.nextOrCreate(s);
                try {
                    c.reserve();
                    break;
                } catch (StaleBranchException e) {
                    // Retry
                }
            }
            release.add(c);
            n = c;
        }
        
        try {
            n.setIfAbsent(v, otherwise);
        } finally {
            release.descendingIterator().forEachRemaining(Node::unreserve);
        }
    }
    
    void clear(Iterable<String> keySegments) {
        final Iterator<String> it = keySegments.iterator();
        
        Node<V> n = root;
        while (it.hasNext())  {
            n = n.next(it.next());
            if (n == null) {
                // Branch doesn't extend further, job done
                return;
            }
        }
        
        if (n.clear()) {
            cleanup.run();
        }
    }
    
    /**
     * Flatten the tree and dump all node values; designed for tests only.<p>
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
        root.entryStream("", delimiter, "").forEach(e -> {
            if (m.putIfAbsent(e.getKey(), e.getValue()) != null) {
                throw new IllegalStateException("Duplicated path/key: " + e.getKey());
            }
        });
        return Collections.unmodifiableMap(m);
    }
    
    /**
     * A node is associated with an arbitrary value and may have descendant
     * children nodes keyed by a string. Each child may in turn contain
     * children, effectively making up a tree.<p>
     * 
     * A tree is built upon demand when being traversed using the method {@link
     * #nextOrCreate(String)}. Each node returned from this method must be
     * {@link #reserve() reserved} until the enclosing operation has completed
     * at which point all the reserved nodes must be {@link #unreserve()
     * unreserved} (ideally in reversed order). This will ensure that a
     * concurrently running {@link #prune() pruning} operation does not delete
     * the link to a reserved node which would otherwise have made the node
     * unreachable.<p>
     * 
     * Reserving a node and setting its value generally do not block and if it
     * does the block is expected to be minuscule. Creating a child node as well
     * as pruning is subject to synchronized monitors as used internally by a
     * {@code ConcurrentHashMap} sub-component. Similarly, these monitors are
     * not expected to be contended or to be held for very long.<p>
     * 
     * Most importantly, traversing the tree uses no locks imposed by this class
     * and does not generally block (at the discretion of the {@code
     * ConcurrentHashMap} sub-component).
     * 
     * @author Martin Andersson (webmaster at martinandersson.com)
     */
    private static class Node<V>
    {
        private final AtomicReference<V> val;
        // Does not allow null to be used as key or value
        private final ConcurrentMap<String, Node<V>> children;
        // Used only for accessing the orphan flag
        private final Lock read, write;
        private boolean orphan;
        
        Node(V v) {
            val = new AtomicReference<>(v);
            children = new ConcurrentHashMap<>();
            
            ReentrantReadWriteLock l = new ReentrantReadWriteLock();
            read = l.readLock();
            write = l.writeLock();
            
            orphan = false;
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
        void reserve() throws StaleBranchException {
            read.lock();
            if (orphan) {
                read.unlock();
                throw new StaleBranchException();
            }
        }
        
        void unreserve() {
            read.unlock();
        }
        
        V get() {
            return val.get();
        }
        
        /**
         * Set the node's value, if absent.<p>
         * 
         * @param v value to associate with node
         * @param otherwise invoked with old value if not successful
         * 
         * @throws NullPointerException if {@code v} is {@code null}
         */
        void setIfAbsent(V v, Consumer<V> otherwise) {
            requireNonNull(v);
            V o;
            if ((o = val.compareAndExchange(null, v)) != null && otherwise != null) {
                otherwise.accept(o);
            }
        }
        
        /**
         * Traverse the tree to a child node.
         *
         * @param segment value
         *
         * @return the child node or {@code null} if it does not exist
         */
        Node<V> next(String segment) {
            return children.get(segment);
        }
        
        /**
         * Traverse the tree to a child node, creating the child if it does not
         * exist.
         *
         * @param segment value
         *
         * @return the child node (never {@code null})
         */
        Node<V> nextOrCreate(String segment) {
            return children.computeIfAbsent(segment, keyIgnored -> new Node<>(null));
        }
        
        /**
         * Remove the node's value.
         *
         * @return {@code true} if operation had an effect (value was previously
         *         set), otherwise {@code false}
         */
        boolean clear() {
            return val.getAndSet(null) != null;
        }
        
        /**
         * Will delete the link to all children nodes that has no associated
         * value and who themselves have no children. The algorithm works
         * recursively and depth-first, returning {@code true} if this node
         * has no value and no children.<p>
         * 
         * This is semantically equivalent to pruning the branch of a tree (or
         * the entire tree if pruning starts at the root) by removing
         * superfluous nodes that serve no purpose other than to take up memory.
         * In fact, pruning must take place; without it we would risk having a
         * memory leak.<p>
         * 
         * It is imperative that a branch-expanding operation also {@link
         * #reserve()} each node it creates. See {@link Node}.
         * 
         * @return {@code true} if this node is an orphan after having
         *         recursively pruned all children depth-first
         */
        boolean prune() {
            children.values().removeIf(Node::prune);
            
            if (!write.tryLock()) {
                return false;
            }
            
            try {
                return (orphan = children.isEmpty() && val.get() == null);
            } finally {
                write.unlock();
            }
        }
        
        Stream<Map.Entry<String, V>> entryStream(String prefix, CharSequence delimiter, String key) {
            final String path = prefix + delimiter + key;
            
            Stream<Map.Entry<String, V>>
                    self = Stream.of(entry(path, get())),
                    kids = children.entrySet().stream().flatMap(e ->
                            e.getValue().entryStream(key.isEmpty() ? "" : path, delimiter, e.getKey()));
            
            return Stream.concat(self, kids);
        }
    }
    
    /**
     * The node has been made orphaned by a pruning operation and is no longer
     * part of an active branch. Client code catching this exception should
     * re-acquire or re-create the node upon which it attempted to operate.
     */
    private static class StaleBranchException extends Exception {
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
}