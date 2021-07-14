package alpha.nomagichttp.events;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * A synchronous implementation of both {@code ScatteringEventEmitter} and
 * {@link EventEmitter}, servicing the subclass with a protected {@link
 * #emit(Object, Object, Object)} method.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public abstract class AbstractScatteringEventEmitter
                extends AbstractEventEmitter
                implements ScatteringEventEmitter
{
    private final Set<TriConsumer<?, ?, ?>> catchAll;
    
    /**
     * Constructs this object using non-blocking and thread-safe data structures
     * to store listeners in.
     */
    protected AbstractScatteringEventEmitter() {
        catchAll = ConcurrentHashMap.newKeySet(1);
    }
    
    /**
     * Constructs this object.
     * 
     * @param catchAllSetImpl to use for catch-all listeners
     * @param listenerMapImpl to use as ordinary listeners' map
     * @param listenerSetImpl to use as ordinary listeners' container (map value)
     */
    protected AbstractScatteringEventEmitter(
            Set<TriConsumer<?, ?, ?>> catchAllSetImpl,
            Map<Class<?>, Set<Object>> listenerMapImpl,
            Supplier<? extends Set<Object>> listenerSetImpl)
    {
        super(listenerMapImpl, listenerSetImpl);
        catchAll = requireNonNull(catchAllSetImpl);
    }
    
    @Override
    protected int emit(Object ev, Object att1, Object att2) {
        int a = super.emit(ev, att1, att2),
            b = emit(catchAll, ev, att1, att2);
        return addExactCapped(a, b);
    }
    
    @Override
    public <T, U, V> boolean onAll(TriConsumer<? super T, ? super U, ? super V> l) {
        requireNonNull(l);
        return catchAll.add(l);
    }
    
    @Override
    public boolean offAll(TriConsumer<?, ?, ?> l) {
        requireNonNull(l);
        return catchAll.remove(l);
    }
    
    private static int addExactCapped(int x, int y) {
        // Copy-paste from Math.addExact
        int r = x + y;
        if (((x ^ r) & (y ^ r)) < 0) {
            return Integer.MAX_VALUE;
        }
        return r;
    }
}