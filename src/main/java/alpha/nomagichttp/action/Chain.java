package alpha.nomagichttp.action;

import alpha.nomagichttp.Config;

/**
 * Proceed or abort the invocation chain of before-actions and the request
 * handler.<p>
 * 
 * The chain object is thread-safe and does not necessarily have to be called
 * by the same thread running the action. In fact, the chief purpose behind
 * this class is to support asynchronous actions that does not complete their
 * job when the action's {@code apply} method returns.<p>
 * 
 * Each chain object passed to the action is unique for that action invocation.
 * Only the first call to a method declared in this interface has an effect.
 * Subsequent invocations are NOP.<p>
 * 
 * It is important that the before-action either returns exceptionally from the
 * {@code apply} method or interacts with the given chain object. Failure to do
 * so will eventually raise a {@linkplain Config#timeoutRead() read} or
 * {@linkplain Config#timeoutResponse() response} timeout.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public interface Chain {
    /**
     * Proceed with calling the next action if there is one, or attempt to
     * resolve and invoke the request handler.<p>
     * 
     * The actual continuation will take place whenever <i>both</i> the
     * invocation of the action returns normally and this method has been
     * called.
     * 
     * @see BeforeAction
     */
    void proceed();
    
    /**
     * Mark the current action invocation as complete and do not continue the
     * call chain.<p>
     * 
     * Aborting causes the server to simply stop executing the before-action and
     * request handler call chain. No subsequent actions will be invoked and the
     * request handler resolution will never begin. The channel - unless closed
     * by the application - remains open and may therefore serve a new HTTP
     * exchange as soon as the final response has been sent.<p>
     * 
     * From the server's perspective, there's really no difference between a
     * aborting the call chain and returning out normally from a request
     * handler. The application must always make that a final response is at
     * some point written to the channel. An action aborting the chain should
     * normally have written the response first, but it is <i>possible</i>
     * (albeit slightly obfuscating) to first abort and then write a
     * response.<p>
     * 
     * An alternative to aborting is to simply throw an exception from the
     * before-action. An exception thrown from the action will be delivered to
     * the error handler(s).
     * 
     * @see BeforeAction
     */
    void abort();
}