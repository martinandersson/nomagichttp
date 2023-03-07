package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.util.Throwing;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static java.lang.Thread.currentThread;
import static java.util.Objects.requireNonNull;

/**
 * A thread-safe and non-blocking box of a confined value.<p>
 * 
 * Mutating access to the value is confined in time and space to a single
 * thread; the initializer during initialization and the disposer whilst
 * dropping. In-between, the value can only be contractually peeked for queries
 * that does not have side effects.<p>
 * 
 * This is a simpler API compared to using an {@code AtomicReference}, and has
 * more relaxed requirements; the update function (initializer) can be costly
 * and have side effects (because it will run exactly-once).<p>
 * 
 * One use case for this class is to hold a reference to a
 * {@code ServerSocketChannel}. Constructing the channel can in theory involve
 * I/O and have system-wide side effects (not something one wishes to do
 * injudiciously in an update function). An extra benefit is being able to bind
 * the channel as part of the initialization which in turn provides stronger
 * semantics for {@link HttpServer#isRunning()}, without sacrificing performance
 * (a value present means that the channel has been both created and bound).
 * 
 * <h2>Memory consistency effects</h2>
 * 
 * Actions in the thread prior to initializing the value
 * <a href="../../../../java.base/java/util/concurrent/package-summary.html#MemoryVisibility">
 * <i>happen-before</i></a> any actions taken by the thread dropping the value.
 * 
 * @jls 17.4.5 Happens-before Order
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @param <V> type of thing in the box
 */
public final class Confined<V>
{
    /**
     * Constructs this object.
     */
    public Confined() {
        // Empty
    }
    
    private final AtomicReference<Object> ref = new AtomicReference<>();
    
    /**
     * An internal operation is free to update the reference to whatever value
     * it sees fit, but only after having first made a reservation.
     * 
     * @param threadId the reservee
     * @param previousVal previous reference value
     */
    private record Reservation(long threadId, Object previousVal) {
        boolean isMine() {
            return threadId == currentThread().threadId();
        }
    }
    
    private static final Reservation
            DROPPED = new Reservation(-1, null);
    
    private static final Predicate<Object>
            IS_USER_VALUE = o -> o != null && !(o instanceof Reservation);
    
    /**
     * Initializes this box with a value.<p>
     * 
     * The box can ever only initialize once. The initializing thread will
     * receive the value in return, all other threads receive {@code null}.<p>
     * 
     * If the {@code factory} returns exceptionally, this box can never
     * initialize again, as if the value was dropped.
     * 
     * @param factory called exactly once by the initializer
     * @return the value if thread was initializer
     * @throws NullPointerException if {@code factory} is {@code null}
     */
    V init(Supplier<? extends V> factory) {
        return initThrowsX(factory::get);
    }
    
    /**
     * Initializes this box with a value.<p>
     * 
     * This method is equivalent to {@link #init(Supplier)}, except it allows
     * for the factory to throw a checked exception.
     * 
     * @param factory called exactly once by the initializer
     * @return the value if thread was initializer
     * @throws NullPointerException if {@code factory} is {@code null}
     */
    <X extends Exception> V initThrowsX(Throwing.Supplier<? extends V, X> factory) throws X {
        requireNonNull(factory);
        Predicate<Object> mustNotBeSetToAnything = Objects::isNull;
        var v1 = reserve(mustNotBeSetToAnything);
        V v2 = null;
        if (v1 instanceof Reservation r && r.isMine()) {
            try {
                v2 = requireNonNull(factory.get());
            } catch (Throwable t) {
                ref.set(DROPPED);
                throw t;
            }
            ref.set(v2);
        }
        return v2;
    }
    
    /**
     * Returns {@code true} if a value is contained in the box.
     * 
     * @return {@code true} if a value is contained in the box
     */
    boolean isPresent() {
        return IS_USER_VALUE.test(ref.get());
    }
    
    /**
     * Returns the confined value.
     * 
     * The call-site must not mutate the value.
     * 
     * @return the confined value
     */
    Optional<V> peek() {
        Object o = ref.get();
        if (IS_USER_VALUE.test(o)) {
            @SuppressWarnings("unchecked")
            V v = (V) o;
            return Optional.of(v);
        }
        return Optional.empty();
    }
    
    /**
     * Drops the contained value to never be seen again.<p>
     * 
     * The {@code disposer} will be called exactly once and only if the value
     * has been initialized. All other calls will be NOP.
     * 
     * @param disposer receives the no longer confined value
     * @return {@code true} if the operation was successful, otherwise it was NOP
     * @throws NullPointerException if {@code disposer} is {@code null}
     */
    boolean drop(Consumer<? super V> disposer) {
        return dropThrowsX(disposer::accept);
    }
    
    /**
     * Drops the contained value to never be seen again.<p>
     * 
     * This method is equivalent to {@link #drop(Consumer)}, except it allows
     * for the disposer to throw a checked exception.
     * 
     * @param disposer receives the no longer confined value
     * @return {@code true} if the operation was successful, otherwise it was NOP
     * @throws NullPointerException if {@code disposer} is {@code null}
     */
    <X extends Exception> boolean dropThrowsX(Throwing.Consumer<? super V, X> disposer) throws X {
        requireNonNull(disposer);
        var v1 = reserve(IS_USER_VALUE);
        if (v1 instanceof Reservation r && r.isMine()) {
            ref.set(DROPPED);
            @SuppressWarnings("unchecked")
            V v = (V) r.previousVal();
            disposer.accept(v);
            return true;
        }
        return false;
    }
    
    /**
     * Conditionally updates the reference value to a Reservation object.
     * 
     * @param when a condition that receives the current reference value
     * 
     * @return if the when-condition matched; the reservation,
     *         otherwise the current untouched value
     */
    private Object reserve(Predicate<Object> when) {
        return ref.updateAndGet(v -> when.test(v) ?
                new Reservation(currentThread().threadId(), v) :
                v);
    }
}