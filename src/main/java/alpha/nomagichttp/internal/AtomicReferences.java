package alpha.nomagichttp.internal;

import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Utilities for {@link AtomicReference}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class AtomicReferences
{
    private AtomicReferences() {
        // Empty
    }
    
    /**
     * Lazily initialize a new value of an atomic reference.<p>
     * 
     * The {@code factory} may be called multiple times when attempted
     * updates fail due to contention among threads. However, the {@code
     * postInit} consumer is only called exactly once by the thread who
     * successfully set the reference value (determined by object identity).<p>
     * 
     * I.e. this method is useful when constructing the value instance is not
     * expensive but post-initialization of the object is, e.g. create a {@code
     * CompletableFuture} but have only one tread compute its result. Other
     * threads can join the result.<p>
     * 
     * Can also be used in a similar way when post-initialization may be cheap
     * but must for whatever reason still only be performed exactly once, e.g.
     * create a {@code CompletableFuture} as a container but have only one
     * thread create a scheduled task (the result).<p>
     * 
     * Note:
     * <ol>
     *   <li>A non-null value is never replaced. If the reference is already
     *       set, neither factory nor initializer is called - the current value
     *       is returned.</li>
     *   <li>Atomic reference is set first, then post initialization runs. I.e.
     *       any thread consuming the reference value may at any time observe
     *       the semantically uninitialized value, even whilst the
     *       initialization operation is running or even if the operation
     *       returned exceptionally.</li>
     *   <li>Therefore, make sure the accumulation type can intrinsically
     *       orchestrate its post-initialized state to multiple threads without
     *       data corruption- or race. This method was designed with a {@code
     *       CompletableFuture} accumulator in mind. E.g., many threads will
     *       observe the same result carrier, but only one of them proceeds to
     *       compute its result.</li>
     * </ol>
     * 
     * @param ref value container/store
     * @param factory value creator
     * @param postInit value initializer
     * @param <V> value type
     * @param <A> accumulation type
     * 
     * @return the value
     * 
     * @throws NullPointerException
     *             if any arg is {@code null}, or
     *             if factory returns {@code null} upon invocation
     */
    static <V, A extends V> V lazyInit(
            AtomicReference<V> ref, Supplier<? extends A> factory, Consumer<? super A> postInit)
    {
        requireNonNull(factory);
        requireNonNull(postInit);
        
        class Bag {
            A thing;
        }
        
        Bag created = new Bag();
        
        V latest = ref.updateAndGet(v -> {
            if (v != null) {
                return v;
            }
            return created.thing = requireNonNull(factory.get());
        });
        
        if (latest == created.thing) {
            postInit.accept(created.thing);
        }
        
        return latest;
    }
    
    /**
     * Lazily initialize a new value of an atomic reference, or else return an
     * alternative value.<p>
     * 
     * This method behaves the same as {@link
     * #lazyInit(AtomicReference, Supplier, Consumer)}, except only the
     * initializing thread will also observe the reference value, all others
     * will get the alternative. This effectively changes the atomic reference
     * function from being a non-discriminatory value container to being
     * reserved only for the initializer.<p>
     * 
     * Think of it as a sort of a concurrency primitive akin to a non-blocking
     * one permit {@link Semaphore Semaphore}{@code .tryAcquire()} where only
     * the initializer succeeds by receiving the permit (a typed value in this
     * case) and the primitive can then be reset (permit released) by setting
     * the reference back to null.
     * 
     * @param ref value container/store
     * @param factory value creator
     * @param postInit value initializer
     * @param alternativeValue to give any thread not also being the initializer
     * @param <V> value type
     * @param <A> accumulation type
     *
     * @return the value if initialized, otherwise the alternative
     * 
     * @throws NullPointerException
     *             if any arg is {@code null}, or
     *             if factory returns {@code null} upon invocation
     */
    static <V, A extends V> V lazyInitOrElse(
            AtomicReference<V> ref, Supplier<? extends A> factory, Consumer<? super A> postInit, V alternativeValue)
    {
        // Copy-pasted from lazyInit(). Implementing DRY through a common method
        // would probably be hard to read/understand?
        
        requireNonNull(factory);
        requireNonNull(postInit);
        
        class Bag {
            A thing;
        }
        
        Bag created = new Bag();
        
        V latest = ref.updateAndGet(v -> {
            if (v != null) {
                return v;
            }
            return created.thing = requireNonNull(factory.get());
        });
        
        if (latest == created.thing) {
            postInit.accept(created.thing);
            return latest; // <-- this is one of two changed lines
        }
        
        return alternativeValue; // <-- this also changed
    }
    
    /**
     * Atomically set the value of the reference to the factory-produced value
     * only if the actual value is {@code null}.
     * 
     * @param ref value container
     * @param newValue to set if actual value is {@code null}
     * @param <V> value type
     * 
     * @return actual value if not {@code null}, otherwise {@code newValue}
     */
    static <V> V setIfAbsent(AtomicReference<V> ref, Supplier<? extends V> newValue) {
        return ref.updateAndGet(act -> {
            if (act != null) {
                return act;
            }
            return newValue.get();
        });
    }
    
    /**
     * Invoke the given action only if the atomic reference stores a non-null
     * value.
     * 
     * @param ref reference target
     * @param action consumer of non-null value
     * @param <V> value type
     * @return {@code true} if present (action called), otherwise {@code false}
     * @throws NullPointerException if any arg is {@code null}
     */
    static <V> boolean ifPresent(AtomicReference<V> ref, Consumer<? super V> action) {
        V v = ref.get();
        if (v == null) {
            requireNonNull(action);
            return false;
        }
        action.accept(v);
        return true;
    }
    
    /**
     * Take the value from the atomic reference and set it to {@code null}.
     * 
     * @param ref reference target
     * @param <V> value type
     * @return an optional with the value
     * @throws NullPointerException if {@code ref} is {@code null}
     */
    static <V> Optional<V> take(AtomicReference<V> ref) {
        return ofNullable(ref.getAndUpdate(v -> null));
    }
    
    /**
     * Take the value from the atomic reference and set it to {@code null}, but
     * only if the current value is not {@code null} and passes the test.<p>
     * 
     * Useful when there's an expectation of the present value and only then is
     * it useful, and, reserved for the call-site's exclusive use.<p>
     * 
     * For example,
     * <pre>
     *   AtomicReference{@literal <}BankAccount{@literal >} acc = ...
     *   takeIf(acc, BankAccount::isLoaded).ifPresent(me::give);
     * </pre>
     * 
     * Note: a returned empty optional means the value was either {@code null}
     * <i>or</i> did not pass the test. There is no support for "take null".
     * 
     * @param ref reference target
     * @param test of current value
     * @param <V> value type
     * @return an optional with the value if it was present and passed the test
     * @throws NullPointerException if any arg is {@code null}
     */
    static <V> Optional<V> takeIf(AtomicReference<V> ref, Predicate<? super V> test) {
        boolean[] memory = new boolean[1];
        V old = ref.getAndUpdate(v ->
                v != null && (memory[0] = test.test(v)) ?
                        /* reset */ null : /* keep */ v);
        return memory[0] ? of(old) : empty();
    }
    
    /**
     * Overload of {@link #takeIf(AtomicReference, Predicate)} where the
     * predicate is a reference equality check ({@code ==}).
     * 
     * For example,
     * <pre>
     *   // Wife obviously does not implement equals(), only what reference we have counts
     *   Wife mine = new Wife();
     *   AtomicReference{@literal <}Wife{@literal >} crashingCarDriver = ...
     *   takeIfSame(crashingCarDriver, mine).ifPresent(me::abandon);
     * </pre>
     * 
     * @param ref reference target
     * @param val tested reference value
     * @param <V> value type
     * @return an optional with {@code val} if {@code val} was the current value
     * @throws NullPointerException if any arg is {@code null}
     */
    static <V> Optional<V> takeIfSame(AtomicReference<V> ref, V val) {
        requireNonNull(val);
        return takeIf(ref, v -> v == val);
    }
}