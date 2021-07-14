package alpha.nomagichttp.events;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.message.RequestHead;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Emits events as they happen, to which, event listeners may come and go.<p>
 * 
 * Which events are emitted should be documented by the emitter. The emission
 * <i>may</i> also carry with it one or two attachments, which are arbitrary
 * objects for passing event-related data.<p>
 * 
 * Events can be used to reduce code duplication and as an alternative to
 * callback arguments. Events can also make code traceability harder and add
 * unnecessary complexity.
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
 * </pre>
 * 
 * Event listeners observe events of a particular runtime type of said event
 * object. Listeners can not subscribe to a superclass and then receive all
 * events that are an instance of the superclass.
 * <pre>
 * 
 *   EventEmitter source = ...
 *   
 *   // Receives new Object(), not "string"
 *   source.on(Object.class, new MyConsumer());
 *   
 *    // Receives "string", not new Object()
 *   source.on(String.class, new MyConsumer());
 *   
 *   Consumer{@literal <}Object{@literal >} receivesBoth = System.out::println;
 *   source.on(Object.class, receivesBoth);
 *   source.on(String.class, receivesBoth);
 * </pre>
 *
 * The same listener instance can be re-subscribed against different event types
 * to accomplish a similar result. If all events are sought after, consider
 * subscribing to a {@link ScatteringEventEmitter}.<p>
 * 
 * Semantics concerning attachments are normally documented and defined by the
 * event type, and, normally always present or never present. For example, a
 * {@link RequestHeadParsed} event raised by the {@link HttpServer#events()
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
 * returned from {@code HttpServer#events()}. A custom implementation is free
 * to behave differently.<p>
 * 
 * Events are not cached. A new listener does not receive past events.<p>
 * 
 * The thread emitting the event is also the thread that invokes listeners of
 * the event. This may be the server's request thread, and so, a listener must
 * not block or take time processing the event. The synchronous nature also
 * means that circular emissions could end up with a {@code
 * StackOverflowError}.<p>
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
 * The listener implementation must have well-behaved implementations of {@code
 * hashCode()} and {@code equals()} as it will be stored in a hash-based data
 * structure. Duplicates not allowed (based on event type key and listener
 * equality).<p>
 * 
 * Lambdas create a new instance. This will subscribe but fail to unsubscribe:
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
 *   Consumer{@literal <}Something{@literal >} listener = event -{@literal >} {};
 *   emitter.on(Something.class, listener);
 *   emitter.off(Something.class, listener);
 * </pre>
 * 
 * There is no special handling/logic concerning exceptions. If a listener
 * throws an exception, then that exception will propagate up the call stack and
 * remaining listeners in the call chain will miss out on the event.<p>
 * 
 * References are kept using strong references (not weak, soft or whatever
 * else). If you need to subscribe magical beans as listeners with a "scope"
 * smaller than the emitter itself, then you should probably also unsubscribe in
 * a "pre destroy" container callback - or you know, do your job and start
 * writing real code.
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