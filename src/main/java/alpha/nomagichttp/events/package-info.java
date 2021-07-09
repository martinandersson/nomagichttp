/**
 * Events are any type of objects emitted by an {@link
 * alpha.nomagichttp.events.EventEmitter EventEmitter} and observed by a
 * consumer (functional interface). An {@link alpha.nomagichttp.events.EventHub}
 * can be used to redistribute events from other event emitters and to
 * programmatically emit events from application code.<p>
 * 
 * The API has been carefully crafted for high performance and throughput. For
 * example, listeners are grouped by event runtime types which makes the default
 * implementation able to use a simple {@code Map} as a backing store of
 * listeners. The lookup operation is uber fast (on average constant time) and
 * does not need to dig around for compatible subtypes or filter on all
 * listeners. There is also no common "Event" supertype that expose methods for
 * the event source and other meta data, this would have reduced type
 * flexibility and increased garbage. Instead, the emitter is encouraged to pass
 * constants representing the event with arbitrary attachments if needed.<p>
 * 
 * Any one implementing an event emitter or using one to subscribe listeners
 * should never have to worry about performance.
 */
package alpha.nomagichttp.events;