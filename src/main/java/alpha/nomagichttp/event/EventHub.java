package alpha.nomagichttp.event;

import java.util.function.Supplier;

import static alpha.nomagichttp.util.Streams.stream;

/**
 * An event emitter that can be used to redistribute events from other emitters
 * and/or programmatically dispatch events from application code. Also commonly
 * referred to on the internet as an "event bus".<p>
 * 
 * An event hub can be used as a middleman to decouple the emitter from the
 * consumer. The NoMagicHTTP server uses it to primarily redistribute events
 * from internal components which are not otherwise exposed to application
 * code.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @see EventEmitter
 */
public interface EventHub extends ScatteringEventEmitter, EventEmitter
{
    /**
     * Combine the given emitters into one hub.<p>
     * 
     * Events from the sourced emitters will be observed by the source's own
     * listeners as well as listeners subscribed to the hub instance returned.
     * Events dispatched through the returned hub will only be observed by the
     * hub's own listeners and not propagate upstream.<p>
     * 
     * Arguments are not checked for duplication. The emitters should be unique.
     * 
     * @param first emitter
     * @param second emitter
     * @param more emitters optionally
     * @return emitters combined into one
     * @throws NullPointerException if any {@code arg} is {@code null}
     */
    static EventHub combine(ScatteringEventEmitter first, ScatteringEventEmitter second, ScatteringEventEmitter... more) {
        EventHub combo = new DefaultEventHub();
        stream(first, second, more).forEach(combo::redistribute);
        return combo;
    }
    
    /**
     * Combine the given emitters into one hub.<p>
     * 
     * Event from the sourced emitters will be observed by the source's own
     * listeners as well as listeners subscribed to the hub. Events dispatched
     * through the hub will only be observed by the hub's listeners and not
     * propagate upstream.<p>
     * 
     * Elements are not checked for duplication. The emitters should be unique.
     * 
     * @param emitters to combine
     * @return emitters combined into one
     * @throws NullPointerException if any element is {@code null}
     */
    static EventHub combine(Iterable<? extends ScatteringEventEmitter> emitters) {
        EventHub combo = new DefaultEventHub();
        emitters.forEach(combo::redistribute);
        return combo;
    }
    
    /**
     * Synchronously dispatch an event to subscribed listeners.
     * 
     * @param event to emit/dispatch
     * @return a count of listeners invoked (capped at {@code Integer.MAX_VALUE})
     * @throws NullPointerException if {@code event} is {@code null}
     * @see EventEmitter
     */
    int dispatch(Object event);
    
    /**
     * Synchronously dispatch an event with an attachment to subscribed
     * listeners.<p>
     * 
     * This method does <i>not</i> throw {@code NullPointerException} if the
     * attachment is {@code null}.
     * 
     * @param event to emit/dispatch
     * @param attachment of event
     * @return a count of listeners invoked (capped at {@code Integer.MAX_VALUE})
     * @throws NullPointerException if {@code event} is {@code null}
     * @see EventEmitter
     */
    int dispatch(Object event, Object attachment);
    
    /**
     * Synchronously dispatch an event with attachments to subscribed
     * listeners.<p>
     * 
     * This method does <i>not</i> throw {@code NullPointerException} if one or
     * both of the attachments are {@code null}.
     * 
     * @param event to emit/dispatch
     * @param attachment1 of event
     * @param attachment2 of event
     * @return a count of listeners invoked (capped at {@code Integer.MAX_VALUE})
     * @throws NullPointerException if {@code event} is {@code null}
     * @see EventEmitter
     */
    int dispatch(Object event, Object attachment1, Object attachment2);
    
    /**
     * Synchronously dispatch an event with a lazy attachment to subscribed
     * listeners.<p>
     * 
     * The attachment is lazily produced only once if there are listeners who
     * will receive it.<p>
     * 
     * This method does <i>not</i> throw {@code NullPointerException} if the
     * attachment produced is {@code null}.
     * 
     * @param  event to emit/dispatch
     * @param  attachment of event
     * @return a count of listeners invoked (capped at {@code Integer.MAX_VALUE})
     * @throws NullPointerException
     *             if {@code event} is {@code null}, or
     *             if {@code attachment} (the Supplier) is {@code null} and
     *             there are listeners for the event (lazy, implicit validation)
     * 
     * @see EventEmitter
     */
    int dispatchLazy(Object event, Supplier<?> attachment);
    
    /**
     * Synchronously dispatch an event with lazy attachments to subscribed
     * listeners.<p>
     * 
     * The attachments are lazily produced only once if there are listeners who
     * will receive it.<p>
     * 
     * This method does <i>not</i> throw {@code NullPointerException} if any one
     * of the attachments produced are {@code null}.
     * 
     * @param  event to emit/dispatch
     * @param  attachment1 of event
     * @param  attachment2 of event
     * @return a count of listeners invoked (capped at {@code Integer.MAX_VALUE})
     * @throws NullPointerException
     *             if {@code event} is {@code null}, or
     *             if any attachment supplier is {@code null} and there are
     *             listeners for the event (lazy, implicit validation)
     * 
     * @see EventEmitter
     */
    int dispatchLazy(Object event, Supplier<?> attachment1, Supplier<?> attachment2);
    
    /**
     * Assign this hub to redistribute all events from the given emitter.<p>
     * 
     * The reference to the given emitter is not stored in the hub and will thus
     * not stop the emitter from being garbage collected.<p>
     * 
     * If the given emitter is an event hub itself, consider using the static
     * method {@link #combine(Iterable)} instead. Combining creates a new hub
     * instance instead of affecting this instance. Consider this example:
     * 
     * <pre>{@code
     *   server1.events().on(ThingCreated.class, (ev) -> globalCounter.increment());
     *   server2.events().on(ThingCreated.class, (ev) -> globalCounter.increment());
     *   // Somewhere else smarty pants creates a "global hub" and exposes it globally
     *   server2.events().redistribute(server1.events());
     *   EventHub global = server2.events();
     *   // Someone uses the reference and unintentionally subscribes a duplicated counter
     *   global.on(ThingCreated.class, (ev) -> globalCounter.increment());
     *   // This will now bump the counter twice
     *   global.dispatch(ThingCreated.INSTANCE);
     * }</pre>
     * 
     * Solution:
     * <pre>{@code
     *   ...
     *   EventHub global = EventHub.combine(server1, server2);
     *   ...
     * }</pre>
     * 
     * @param emitter to redistribute
     * @throws NullPointerException if {@code emitter} is {@code null}
     * @throws IllegalArgumentException if {@code emitter} is {@code this}
     */
    void redistribute(ScatteringEventEmitter emitter);
}