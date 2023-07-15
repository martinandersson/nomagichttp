package alpha.nomagichttp.event;

import alpha.nomagichttp.util.TriConsumer;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/**
 * A synchronous implementation of both {@code ScatteringEventEmitter} and
 * {@link EventEmitter}, servicing the subclass with a protected {@link
 * #emit(Object, Object, Object)} method.<p>
 * 
 * This class stores all listeners in non-blocking and thread-safe data
 * structures.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public abstract class AbstractScatteringEventEmitter
                extends AbstractEventEmitter
                implements ScatteringEventEmitter
{
    private final Set<TriConsumer<?, ?, ?>> catchAll = ConcurrentHashMap.newKeySet(1);
    
    /**
     * Constructs this object.
     */
    protected AbstractScatteringEventEmitter() {
        // Empty
    }
    
    /**
     * Constructs this object.
     * 
     * @param when see {@link AbstractEventEmitter}
     * @param decorator see {@link AbstractEventEmitter}
     * @throws NullPointerException if any argument is {@code null}
     */
    protected AbstractScatteringEventEmitter(
            BooleanSupplier when, Consumer<Runnable> decorator) {
        super(when, decorator);
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