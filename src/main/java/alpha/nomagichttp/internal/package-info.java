/**
 * The one and only library-provided server implementation.<p>
 * 
 * The only public type in this package is {@link
 * alpha.nomagichttp.internal.DefaultServer}, which is used by the {@link
 * alpha.nomagichttp.HttpServer} interface as the default implementation. All
 * other types in this package can therefore be regarded as an implementation
 * detail.<p>
 * 
 * Similar to types found in other packages, implementations of the API provided
 * by this package also use the "Default" name-prefix.<p>
 * 
 * 
 * <h2>HTTP server patterns</h2>
 * 
 * TODO: Describe Java 1.7's "proactive" asynchronous model, how this is better
 * than the non-blocking (or blocking) "reactive" Selector API from Java 1.4,
 * and the really old blocking model since Java 1.0, and why we made the choices
 * we did (for example, why we skipped Netty).
 * 
 * 
 * <h2>Threading model specifics</h2>
 * 
 * TODO: Describe AsynchronousChannelGroup and how new client connections can be
 * handled concurrently despite only one accept operation can be outstanding in
 * any given time. Also describe how the group is shared with read/write
 * operations of the children, et cetera. Only specifics; generally speaking
 * the thread model should be described somewhere else.
 *
 * 
 * <h3>Operators, aka Flow Processors</h3>
 * 
 * The operators in this package does not implement the {@code Flow.Processor}
 * interface. These operators are more of a trait or a feature that is added to
 * an upstream publisher by using the decorator pattern. Instead of external
 * subscription at some point of time in the future, the upstream has to be
 * passed as a constructor argument to which the operator immediately
 * subscribes.<p>
 * 
 * Utilizing the decorator pattern instead of implementing {@code
 * Flow.Processor} also makes it perfectly clear that the operators can <i>only
 * act as a subscriber once</i>. It also reduces the operator's public API and
 * even makes the implementation simpler in terms of upstream reference
 * management.
 */
package alpha.nomagichttp.internal;