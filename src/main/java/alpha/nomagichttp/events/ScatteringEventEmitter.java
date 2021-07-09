package alpha.nomagichttp.events;

/**
 * Semantically equivalent to {@link EventEmitter}, except this interface
 * defines methods to subscribe a listener that will receive all events emitted
 * no matter the event type.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public interface ScatteringEventEmitter {
    /**
     * Subscribe a listener.
     * 
     * @param listener receiver of events
     * @param <T> call-site inferred type argument of event
     * @param <U> call-site inferred type of the first attachment
     * @param <V> call-site inferred type of the second attachment
     * 
     * @return {@code true} if subscribed, otherwise {@code false}
     * 
     * @throws NullPointerException if {@code listener} is {@code null}
     */
    <T, U, V> boolean onAll(TriConsumer<? super T, ? super U, ? super V> listener);
    
    /**
     * Unsubscribe a listener.
     * 
     * @param listener receiver of events
     * @return {@code true} if removed, otherwise {@code false}
     * @throws NullPointerException if {@code listener} is {@code null}
     */
    boolean offAll(TriConsumer<?, ?, ?> listener);
}