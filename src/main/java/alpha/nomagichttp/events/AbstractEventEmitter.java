package alpha.nomagichttp.events;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * A synchronous, non-blocking and thread-safe implementation of {@link
 * EventEmitter} servicing the subclass with a protected
 * {@link #emit(Object, Object, Object)} method.<p>
 * 
 * The implementation is backed by a {@code Map} of event types to a {@code Set}
 * of listeners.<p>
 * 
 * Implementations that know in advance what events will be emitted ought to
 * override {@link #supports(Class)}.<p>
 * 
 * By default, the emitter will be backed by concurrent data structures. But
 * this can be customized. For example, here's how to created an emitter which
 * is not thread-safe: 
 * <pre>
 *   class UnsafeLocalEmitter extends AbstractEventEmitter {
 *       UnsafeLocalEmitter() {
 *           super(new HashMap{@literal <}{@literal >}(), HashSet::new);
 *       }
 *   }
 * </pre>
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public abstract class AbstractEventEmitter implements EventEmitter
{
    private final Map<Class<?>, Set<Object>> listeners;
    private final Supplier<? extends Set<Object>> setImpl;
    
    /**
     * Constructs a non-blocking and thread-safe emitter backed by a {@link
     * ConcurrentHashMap} and value-sets from the same type.
     */
    protected AbstractEventEmitter() {
        // does not allow null to be used as a key or value
        // 99.999% of all access are going to be reads, which are blazingly fast
        this(new ConcurrentHashMap<>(), ConcurrentHashMap::newKeySet);
    }
    
    /**
     * Constructs an emitter.
     * 
     * @param mapImpl to use as listeners' map
     * @param setImpl to use as listeners' container (map value)
     * @throws NullPointerException if any arg is {@code null}
     */
    protected AbstractEventEmitter(
            Map<Class<?>, Set<Object>> mapImpl,
            Supplier<? extends Set<Object>> setImpl)
    {
        this.listeners = requireNonNull(mapImpl);
        this.setImpl   = requireNonNull(setImpl);
    }
    
    /**
     * Synchronously emit an event to subscribed listeners.
     * 
     * @param ev to emit
     * @param att1 optional attachment (may be {@code null})
     * @param att2 optional attachment (may be {@code null})
     * @return a count of listeners invoked (capped at {@code Integer.MAX_VALUE})
     * @throws NullPointerException if {@code ev} is {@code null}
     */
    protected int emit(Object ev, Object att1, Object att2) {
        var s = listeners.getOrDefault(ev.getClass(), Set.of());
        return emit(s, ev, att1, att2);
    }
    
    /**
     * Synchronously invoke all listeners with the given event and attachments.
     * 
     * @param listeners to invoke
     * @param ev to emit
     * @param att1 optional attachment (may be {@code null})
     * @param att2 optional attachment (may be {@code null})
     * @return a count of listeners invoked (capped at {@code Integer.MAX_VALUE})
     * @throws NullPointerException if {@code ev} is {@code null}
     */
    protected static int emit(Iterable<? extends Object> listeners, Object ev, Object att1, Object att2) {
        int n = 0;
        for (Object l : listeners) {
            // An early implementation used a "ListenerProxy" so that the type
            // check and casting was only performed once in the operations on/off.
            // The idea was not bad: many emitters likely emit vastly more events
            // over time than subscription operations, so we'd rather take that
            // cost early on and let the emissions be fast[er].
            //     Albeit this is not always the case of course. Some emitters
            // used internally by the server are very short-lived and has only
            // one listener. So they would instead suffer and pay an extra cost
            // of creating the proxy object for no gain. Then, early
            // implementation drafts exposed "profiles" that could be used to
            // tailor these details, but complexity only grew until reason took
            // over and decided that "early optimization is the root of all evil".
            if (l instanceof Consumer) {
                Consumer<Object> uni = retype(l);
                uni.accept(ev);
            } else if (l instanceof BiConsumer) {
                BiConsumer<Object, Object> bi = retype(l);
                bi.accept(ev, att1);
            } else {
                TriConsumer<Object, Object, Object> tri = retype(l);
                tri.accept(ev, att1, att2);
            }
            if (n < Integer.MAX_VALUE) {
                ++n;
            }
        }
        return n;
    }
    
    @SuppressWarnings("unchecked")
    private static <T> T retype(Object thing) {
        return (T) thing;
    }
    
    @Override
    public <T> boolean on(Class<T> eventType, Consumer<? super T> listener) {
        return addListener(eventType, listener);
    }
    
    @Override
    public <T, U> boolean on(Class<T> eventType, BiConsumer<? super T, ? super U> listener) {
        return addListener(eventType, listener);
    }
    
    @Override
    public <T, U, V> boolean on(Class<T> eventType, TriConsumer<? super T, ? super U, ? super V> listener) {
        return addListener(eventType, listener);
    }
    
    @Override
    public <T> boolean off(Class<T> eventType, Consumer<? super T> listener) {
        return removeListener(eventType, listener);
    }
    
    @Override
    public <T> boolean off(Class<T> eventType, BiConsumer<? super T, ?> listener) {
        return removeListener(eventType, listener);
    }
    
    @Override
    public <T> boolean off(Class<T> eventType, TriConsumer<? super T, ?, ?> listener) {
        return removeListener(eventType, listener);
    }
    
    /**
     * Returns {@code true} if the event type is knowingly not supported,
     * otherwise {@code false}, in which case an {@code
     * IllegalArgumentException} will be thrown by the {@code on} methods.<p>
     * 
     * The implementation in this class always returns true.
     * 
     * @param eventType event type (never {@code null})
     * @return {@code true} if the event type is knowingly not supported,
     *         otherwise {@code false}
     */
    protected boolean supports(Class<?> eventType) {
        return true;
    }
    
    private boolean addListener(Class<?> eventType, Object listener) {
        requireNotInterface(eventType);
        return listeners.computeIfAbsent(eventType, k -> setImpl.get())
                .add(listener);
    }
    
    private boolean removeListener(Class<?> eventType, Object listener) {
        requireNotInterface(eventType);
        var set = listeners.get(eventType);
        return set != null && set.remove(listener);
    }
    
    private static void requireNotInterface(Class<?> eventType) {
        // Annotation is an interface
        if (eventType.isInterface()) {
            throw new IllegalArgumentException("Event type can not be an interface.");
        }
    }
}
