package alpha.nomagichttp.action;

import alpha.nomagichttp.message.ResponseTimeoutException;

/**
 * Proceed or abort the continuation of the invocation of before actions and the
 * subsequent request handler.
 * 
 * The chain object is thread-safe and does not necessarily have to be called
 * by the same thread invoking the action. In fact, the chief purpose behind
 * this class is to support asynchronous actions that does not complete their
 * job when the action's {@code apply} method returns.<p>
 * 
 * Only the first invocation of a method declared in this interface has an
 * effect. Subsequent invocations are NOP.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public interface Chain {
    /**
     * Proceed with calling the next action if there is one, or continue with
     * the request handler.<p>
     * 
     * The actual continuation will take place whenever <i>both</i> the
     * invocation of the action returns and this method has been called.
     * 
     * @see BeforeAction
     */
    void proceed();
    
    /**
     * Mark the current action invocation as complete and abort the call
     * chain.<p>
     * 
     * Aborting causes the server to simply stop executing the before-action and
     * request handler call chain. No subsequent actions will be invoked and the
     * request handler resolution will never begin. The channel - unless closed
     * by the application - remains open and may therefore serve a new HTTP
     * exchange as soon as the final response has been sent.<p>
     * 
     * An action aborting the chain should normally also have first written a
     * final response. It is <i>possible</i> (but slightly obfuscating) to first
     * abort and then write a response. Aborting and never write a response at
     * all will eventually cause a {@link ResponseTimeoutException} to be
     * thrown.
     * 
     * @see BeforeAction
     */
    void abort();
}