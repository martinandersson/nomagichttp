/**
 * Events are any type of objects emitted by an {@link
 * alpha.nomagichttp.event.EventEmitter EventEmitter} and observed by a
 * consumer (functional interface). An {@link alpha.nomagichttp.event.EventHub}
 * can be used to redistribute events from other event emitters and to
 * programmatically emit (aka. "dispatch") events from application code.<p>
 * 
 * Event classes are normally co-located in the same feature/component package
 * from where they originate. For the moment, all events emitted by the {@link
 * alpha.nomagichttp.HttpServer#events() HttpServer} are enum literals located
 * in this package. A future release — if and when the NoMagicHTTP project
 * becomes modularized — may change the location.<p>
 * 
 * The API has been carefully designed for simplicity, high performance and
 * throughput. For example, listeners are grouped by event runtime types which
 * makes the default implementation able to use a simple {@code Map} as a
 * backing store of listeners. The lookup operation is uber fast (on average
 * constant time) and does not need to dig around for compatible subtypes or
 * filter on all listeners. There is also no common "Event" supertype that
 * expose methods for accessing the event emitter and other metadata, this would
 * have reduced type flexibility and increased garbage. Instead, the emitter is
 * encouraged to pass constants representing the event with arbitrary
 * attachments when needed.<p>
 * 
 * A developer implementing an event emitter or using one to subscribe listeners
 * should never have to worry about performance.
 */
package alpha.nomagichttp.event;