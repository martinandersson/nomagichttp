package alpha.nomagichttp.util;

import jdk.incubator.concurrent.StructureViolationException;
import jdk.incubator.concurrent.StructuredTaskScope;

import java.util.NoSuchElementException;
import java.util.concurrent.Callable;

/**
 * An insanely stupid implementation mimicking the API of
 * {@code ScopedValue}.<p>
 * 
 * The method signatures in this class and their JavaDoc has been copy and
 * pasted from the JDK's {@code jdk.incubator.concurrent.ScopedValue}. This
 * class serves as a temporary replacement until Gradle supports Java 21, at
 * which time we will upgrade to Java 21 and call sites will simply replace the
 * {@code DummyScopedValue} with a real {@code ScopedValue}.<p>
 * 
 * This class supports binding a value, but not rebinding. An attempt to rebind
 * will throw an {@link UnsupportedOperationException}.
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
     * {@snippet lang=java :
     *     // @link substring="call" target="Carrier#call(Callable)" :
     *     ScopedValue.where(key, value).call(op);
     * }
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
        key.set(value);
        return op.call();
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
     * {@snippet lang=java :
     *     // @link substring="run" target="Carrier#run(Runnable)" :
     *     ScopedValue.where(key, value).run(op);
     * }
     *
     * @param key the {@code ScopedValue} key
     * @param value the value, can be {@code null}
     * @param <T> the type of the value
     * @param op the operation to call
     */
    public static <T> void where(DummyScopedValue<T> key, T value, Runnable op) {
        key.set(value);
        op.run();
    }
    
    private final ThreadLocal<T> value;
    
    private DummyScopedValue() {
        value = new ThreadLocal<>();
    }
    
    /**
     * (main description missing in JDK)
     * 
     * @return the value of the scoped value if bound in the current thread
     * 
     * @throws NoSuchElementException if the scoped value is not bound
     */
    public T get() {
        T t = value.get();
        if (t == null) {
            throw new NoSuchElementException();
        }
        return t;
    }
    
    private void set(T value) {
        if (isBound()) {
            throw new UnsupportedOperationException("Rebinding");
        }
        this.value.set(value);
    }
    
    /**
     * {@return {@code true} if this scoped value is bound in the current thread}
     */
    public boolean isBound() {
        return value.get() != null;
    }
}