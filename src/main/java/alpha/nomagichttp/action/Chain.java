package alpha.nomagichttp.action;

/**
 * Proceed or abort the continuation of the invocation of before actions and the
 * subsequent request handler resolution.
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
     * Mark the current action invocation as completed and proceed with calling
     * the next action if there is one, or continue with the request handler
     * resolution.
     * 
     * @see BeforeAction
     */
    void proceed();
    
    /**
     * Mark the current action invocation as complete and abort the HTTP
     * exchange.<p>
     * 
     * Aborting causes the server to simply drop processing the before-action
     * and request handler call stack. No subsequent actions will be invoked and
     * the request handler resolution will never begin. The channel - unless
     * closed by the application - remains open.<p>
     * 
     * An action aborting the chain should normally also have first written a
     * final response. It is <i>possible</i> (but slightly obfuscating) to first
     * abort and then write a response.
     * 
     * @see BeforeAction
     */
    void abort();
}