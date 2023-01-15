package alpha.nomagichttp.event;

import java.util.function.Supplier;

/**
 * Non-blocking implementation of {@link EventHub}.<p>
 * 
 * The behavior of this class is documented in {@link EventEmitter}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class DefaultEventHub extends AbstractScatteringEventEmitter implements EventHub
{
    /**
     * Constructs a {@code DefaultEventHub}.
     */
    public DefaultEventHub() {
        // Intentionally empty
    }
    
    @Override
    public int dispatch(Object event) {
        return emit(event, null, null);
    }
    
    @Override
    public int dispatch(Object event, Object attachment) {
        return emit(event, attachment, null);
    }
    
    @Override
    public int dispatch(Object event, Object att1, Object att2) {
        return emit(event, att1, att2);
    }
    
    @Override
    public int dispatchLazy(Object event, Supplier<?> attachment) {
        return emitLazy(event, attachment, () -> null);
    }
    
    @Override
    public int dispatchLazy(Object event, Supplier<?> attachment1, Supplier<?> attachment2) {
        return emitLazy(event, attachment1, attachment2);
    }
    
    @Override
    public void redistribute(ScatteringEventEmitter emitter) {
        if (this == emitter) {
            throw new IllegalArgumentException("Can not redistribute from self.");
        }
        emitter.onAll(this::dispatch);
    }
}