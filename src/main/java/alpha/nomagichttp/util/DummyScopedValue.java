package alpha.nomagichttp.util;

import jdk.incubator.concurrent.StructureViolationException;
import jdk.incubator.concurrent.StructuredTaskScope;

import java.util.Deque;
import java.util.LinkedList;
import java.util.NoSuchElementException;
import java.util.concurrent.Callable;

/**
 * A stupid version of {@code ScopedValue}.<p>
 * 
 * The method signatures in this class and their JavaDoc have been copy and
 * pasted from the JDK's {@code jdk.incubator.concurrent.ScopedValue}. This
 * class serves as a temporary stand-in until Gradle supports Java 20, at
 * which time we will upgrade to Java 20 (or 21), and call-sites will simply
 * replace the {@code DummyScopedValue} with a real {@code ScopedValue}.<p>
 * 
 * This class is stupid, because it is backed by thread-locals, which is sort of
 * the thing <a href="https://openjdk.org/jeps/429">JEP-429</a> is trying to
 * replace for virtual threads.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @param <T> the type of the object bound to this ScopedValue
 */
public final class DummyScopedValue<T>
{
    /**
     * Creates a scoped value that is initially unbound for all threads.
     *
     * @param <T> the type of the value
     * @return a new {@code ScopedValue}
     */
    public static <T> DummyScopedValue<T> newInstance() {
        return new DummyScopedValue<>();
    }
    
    /**
     * Calls a value-returning operation with a {@code ScopedValue} bound to a value
     * in the current thread. When the operation completes (normally or with an
     * exception), the {@code ScopedValue} will revert to being unbound, or revert to
     * its previous value when previously bound, in the current thread.
     *
     * <p> Scoped values are intended to be used in a <em>structured manner</em>.
     * If {@code op} creates a {@link StructuredTaskScope} but does not {@linkplain
     * StructuredTaskScope#close() close} it, then exiting {@code op} causes the
     * underlying construct of each {@code StructuredTaskScope} created in the
     * dynamic scope to be closed. This may require blocking until all child threads
     * have completed their sub-tasks. The closing is done in the reverse order that
     * they were created. Once closed, {@link StructureViolationException} is thrown.
     *
     * @implNote
     * This method is implemented to be equivalent to:
     * {@snippet lang = java:
     *     // @link substring="call" target="Carrier#call(Callable)" :
     *     ScopedValue.where(key, stack).call(op);
     *}
     *
     * @param key the {@code ScopedValue} key
     * @param value the value, can be {@code null}
     * @param <T> the type of the value
     * @param <R> the result type
     * @param op the operation to call
     * @return the result
     * @throws Exception if the operation completes with an exception
     */
    public static <T, R> R where(DummyScopedValue<T> key,
                                 T value,
                                 Callable<? extends R> op) throws Exception {
        key.add(value);
        try {
            return op.call();
        } finally {
            key.remove();
        }
    }
    
    /**
     * Run an operation with a {@code ScopedValue} bound to a value in the current
     * thread. When the operation completes (normally or with an exception), the
     * {@code ScopedValue} will revert to being unbound, or revert to its previous value
     * when previously bound, in the current thread.
     *
     * <p> Scoped values are intended to be used in a <em>structured manner</em>.
     * If {@code op} creates a {@link StructuredTaskScope} but does not {@linkplain
     * StructuredTaskScope#close() close} it, then exiting {@code op} causes the
     * underlying construct of each {@code StructuredTaskScope} created in the
     * dynamic scope to be closed. This may require blocking until all child threads
     * have completed their sub-tasks. The closing is done in the reverse order that
     * they were created. Once closed, {@link StructureViolationException} is thrown.
     *
     * @implNote
     * This method is implemented to be equivalent to:
     * {@snippet lang = java:
     *     // @link substring="run" target="Carrier#run(Runnable)" :
     *     ScopedValue.where(key, stack).run(op);
     *}
     *
     * @param key the {@code ScopedValue} key
     * @param value the value, can be {@code null}
     * @param <T> the type of the value
     * @param op the operation to call
     */
    public static <T> void where(DummyScopedValue<T> key, T value, Runnable op) {
        key.add(value);
        try {
            op.run();
        } finally {
            key.remove();
        }
    }
    
    private final InheritableThreadLocal<Deque<T>> stack;
    
    private DummyScopedValue() {
        var tl = new InheritableThreadLocal<Deque<T>>();
        // ArrayDeque does not permit null, LinkedList does
        tl.set(new LinkedList<>());
        stack = tl;
    }
    
    /**
     * (main description missing in JDK)
     * 
     * @return the value of the scoped value if bound in the current thread
     * 
     * @throws NoSuchElementException if the scoped value is not bound
     */
    public T get() {
        T t = stack.get().peekLast();
        if (t == null) {
            throw new NoSuchElementException();
        }
        return t;
    }
    
    private void add(T value) {
        this.stack.get().addLast(value);
    }
    
    private void remove() {
        this.stack.get().removeLast();
    }
    
    /**
     * {@return {@code true} if this scoped value is bound in the current thread}
     */
    public boolean isBound() {
        return stack.get().peekLast() != null;
    }
}