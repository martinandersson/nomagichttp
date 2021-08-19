package alpha.nomagichttp.events;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.message.RequestHead;
import alpha.nomagichttp.util.TriConsumer;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Emits events as they happen, to which, event listeners may come and go.<p>
 * 
 * Which events are emitted should be documented by the emitter. The emission
 * <i>may</i> also carry with it one or two attachments, which are arbitrary
 * objects for passing event-related data. The event object itself can therefore
 * be a constant without any need to create objects and produce unnecessary
 * garbage.<p>
 * 
 * Events can be used to decouple components and to simplify the architecture,
 * e.g. as an alternative to callback arguments. But, events can also make code
 * traceability harder and add unnecessary complexity. With great power comes
 * great responsibility! Events should never be a replacement of what would
 * otherwise have been a simple method call even if that entails coupling.
 * Normally, only when the side-effect is clearly a different concern <i>and</i>
 * there are at least two side-effects happening should decoupling using events
 * be considered an option.
 * 
 * <pre>
 *   class ShoppingCart extends {@link AbstractEventEmitter} {
 *       enum ItemAdded { INSTANCE }
 *       public void addItem(Item thing) {
 *           this.doStuff();
 *           super.emit(ItemAdded.INSTANCE, thing); // {@literal <}-- look, no objects created
 *       }
 *   }
 *   // somewhere else
 *   Inventory inv = ...
 *   someCart.on(ItemAdded.class, inv::reserveAsync);
 *   // and also somewhere else
 *   Counter metrics = ...
 *   someCart.on(ItemAdded.class, metrics::increment);
 * </pre>
 * 
 * Event listeners observe events of the runtime type to which they subscribe.
 * Listeners can not subscribe to a superclass and then receive events that are
 * a subtype of the superclass. Think of the backing data structure as a {@code
 * Map} from event runtime type to a bunch of listeners invoked for that
 * specific event type (that's literally how the default implementation works).
 * <pre>
 * 
 *   EventEmitter source = ...
 *   
 *   // Receives only new Object(), not "string"
 *   source.on(Object.class, new MyConsumer());
 *   
 *    // Receives only "string", not new Object()
 *   source.on(String.class, new MyConsumer());
 *   
 *   // One may register the same listener for multiple types
 *   Consumer{@literal <}Object{@literal >} receivesBoth = System.out::println;
 *   source.on(Object.class, receivesBoth);
 *   source.on(String.class, receivesBoth);
 * </pre>
 * 
 * A {@link ScatteringEventEmitter} supports subscribing to <i>all</i>
 * events.<p>
 * 
 * Semantics concerning attachments are normally documented and defined by the
 * event type, and, normally always present or never present. For example, a
 * {@link RequestHeadReceived} event raised by the {@link HttpServer#events()
 * HttpServer} will always carry with it the {@link RequestHead} object as the
 * first and only attachment.<p>
 * 
 * However, the {@code EventEmitter} interface does not define any requirements
 * what so ever concerning the presence or type of the attachments. Technically,
 * an attachment can be given to the listener conditionally and/or independent
 * of the event type. Technically, attachments can even vary in the runtime type
 * of the given arguments - although, this would certainly be quite
 * confusing.<p>
 * 
 * The listener's functional type may accept none, one or two attachments,
 * independently of whether or not the event emitter actually has attachments to
 * give. If the emitter emits no attachment but the listener declares them, then
 * the received arguments will be {@code null}. If the emitter pass attachments
 * but the consumer does not consume them, then the arguments will be
 * discarded. The arguments will <i>not</i> affect which listener gets or
 * doesn't get invoked, only event type matters.<p>
 * 
 * The {@code EventEmitter} interface does not declare an "emit()" method. How
 * exactly the implementation emits events is an implementation detail, and,
 * in general, it really shouldn't be done by other components than the class
 * itself. Implementations can extend {@link AbstractEventEmitter} and
 * application code that wish to utilize a centralized distribution source can
 * dispatch and observe events through an {@link EventHub}.<p>
 * 
 * There is no back-pressure control. If needed, this could be added by
 * connecting a third-party library as the consumer.<p>
 * 
 * <strong>The remaining JavaDoc</strong> describes the implementations of event
 * emitters provided by the NoMagicHTTP library, including the {@link EventHub}
 * returned from {@link HttpServer#events()}. A custom implementation is free
 * to behave differently.<p>
 * 
 * Events are not saved. A new listener does not receive past events.<p>
 * 
 * The thread emitting the event is also the thread that invokes listeners of
 * the event. This may be the server's request thread, and so, a listener must
 * not block or take time processing the event.<p>
 * 
 * The event emitter (i.e. subscribing and unsubscribing a listener) is
 * non-blocking and thread-safe. The listener, however, may be invoked
 * concurrently and so must be thread-safe.<p>
 * 
 * There is no defined limit to how many listeners may be active.<p>
 * 
 * Event emission order may and should in many cases be defined; {@code
 * Thing.Created} before {@code Thing.Destroyed}. But the order of listener
 * invocations is undefined. Listener B who subscribed last may be called before
 * listener A who subscribed first.<p>
 * 
 * The listener implementation must have <i>well-behaved</i> implementations of
 * {@code hashCode()} and {@code equals()} as it will be stored in a hash-based
 * data structure. Duplicates not allowed (based on event type key and
 * listener's object equality).<p>
 * 
 * Lambdas create different instances. This will subscribe but fail to
 * unsubscribe:
 * <pre>
 *   EventEmitter emitter = ...
 *   // True
 *   emitter.on(Something.class, System.out::println);
 *   // False
 *   emitter.off(Something.class, System.out::println);
 * </pre>
 * 
 * Solution:
 * <pre>
 * 
 *   Consumer{@literal <}Something{@literal >} listener = System.out::println;
 *   emitter.on(Something.class, listener);
 *   emitter.off(Something.class, listener);
 * </pre>
 * 
 * There is no special handling/logic concerning exceptions. If a listener
 * throws an exception, then that exception will propagate up the call stack and
 * remaining listeners in the call chain will miss out on the event. For the
 * HTTP server's events, throwing an exception from the listener may have
 * undefined application behavior.<p>
 * 
 * References are kept using strong references (not weak, soft or whatever
 * else). If one need to subscribe magical beans as listeners with a "scope"
 * smaller than the emitter itself, then one should probably also unsubscribe in
 * a "pre destroy" container callback - or you know, just stop all the magic and
 * start writing real code instead.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public interface EventEmitter {
    /**
     * Subscribe a listener.
     * 
     * @param eventType invariant class of events received by the listener
     * @param listener receiver of events
     * @param <T> type argument of event
     * 
     * @return {@code true} if subscribed, otherwise {@code false}
     * 
     * @throws NullPointerException
     *             if any arg is {@code null}
     * 
     * @throws IllegalArgumentException
     *             if {@code eventType} is an interface, or
     *             if {@code eventType} is known to never be emitted
     */
    <T> boolean on(Class<T> eventType, Consumer<? super T> listener);
    
    /**
     * Subscribe a listener that expects an attachment.
     * 
     * @param eventType invariant class of events received by the listener
     * @param listener receiver of events, possibly with an attachment
     * @param <T> type argument of event
     * @param <U> call-site inferred type of the attachment
     * 
     * @return {@code true} if subscribed, otherwise {@code false}
     * 
     * @throws NullPointerException
     *             if any arg is {@code null}
     * 
     * @throws IllegalArgumentException
     *             if {@code eventType} is an interface, or
     *             if {@code eventType} is known to never be emitted
     */
    <T, U> boolean on(Class<T> eventType, BiConsumer<? super T, ? super U> listener);
    
    /**
     * Subscribe a listener that expects two attachments.
     * 
     * @param eventType invariant class of events received by the listener
     * @param listener receiver of events, possibly with attachments
     * @param <T> type argument of event
     * @param <U> call-site inferred type of the first attachment
     * @param <V> call-site inferred type of the second attachment
     *
     * @return {@code true} if subscribed, otherwise {@code false}
     *
     * @throws NullPointerException
     *             if any arg is {@code null}
     *
     * @throws IllegalArgumentException
     *             if {@code eventType} is an interface, or
     *             if {@code eventType} is known to never be emitted
     */
    <T, U, V> boolean on(Class<T> eventType, TriConsumer<? super T, ? super U, ? super V> listener);
    
    /**
     * Unsubscribe a listener.
     * 
     * @param eventType invariant class of events received by the listener
     * @param listener to unsubscribe
     * @param <T> type argument of event
     * 
     * @return {@code true} if removed, otherwise {@code false}
     * 
     * @throws NullPointerException if any arg is {@code null}
     */
    <T> boolean off(Class<T> eventType, Consumer<? super T> listener);
    
    /**
     * Unsubscribe a listener.
     * 
     * @param eventType invariant class of events received by the listener
     * @param listener to unsubscribe
     * @param <T> type argument of event
     * 
     * @return {@code true} if removed, otherwise {@code false}
     * 
     * @throws NullPointerException if any arg is {@code null}
     */
    <T> boolean off(Class<T> eventType, BiConsumer<? super T, ?> listener);
    
    /**
     * Unsubscribe a listener.
     * 
     * @param eventType invariant class of events received by the listener
     * @param listener to unsubscribe
     * @param <T> type argument of event
     * 
     * @return {@code true} if removed, otherwise {@code false}
     * 
     * @throws NullPointerException if any arg is {@code null}
     */
    <T> boolean off(Class<T> eventType, TriConsumer<? super T, ?, ?> listener);
}