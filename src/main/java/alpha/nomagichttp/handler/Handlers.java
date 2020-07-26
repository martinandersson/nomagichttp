package alpha.nomagichttp.handler;

import alpha.nomagichttp.message.MediaType;

/**
 * Utility methods for building the default handler implementation without any
 * regards related to media types. All handlers returned by this class consumes
 * {@link MediaType#NOTHING_AND_ALL} and produces {@link MediaType#ALL}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class Handlers
{
    private Handlers() {
        // Empty
    }
    
    /**
     * Utility method to construct a GET handler that does not care about the
     * presence or value of the "Content-Type" and "Accept" header in the
     * inbound request.<p>
     * 
     * The returned HandlerBuilder will be positioned at the last step where the
     * logic of the handler must be specified.
     * 
     * @return an almost complete HandlerBuilder
     * 
     * @see HandlerBuilder
     */
    public static HandlerBuilder.LastStep GET() {
        return method("GET");
    }
    
    /**
     * Utility method to construct a POST handler that does not care about the
     * presence or value of the "Content-Type" and "Accept" header in the
     * inbound request.<p>
     *
     * The returned HandlerBuilder will be positioned at the last step where the
     * logic of the handler must be specified.
     *
     * @return an almost complete HandlerBuilder
     *
     * @see HandlerBuilder
     */
    public static HandlerBuilder.LastStep POST() {
        return method("POST");
    }
    
    /**
     * Utility method to construct a PUT handler that does not care about the
     * presence or value of the "Content-Type" and "Accept" header in the
     * inbound request.<p>
     *
     * The returned HandlerBuilder will be positioned at the last step where the
     * logic of the handler must be specified.
     *
     * @return an almost complete HandlerBuilder
     *
     * @see HandlerBuilder
     */
    public static HandlerBuilder.LastStep PUT() {
        return method("PUT");
    }
    
    public static HandlerBuilder.LastStep method(String method) {
        return new HandlerBuilder(method).consumesNothingAndAll().producesAll();
    }
    
    // TODO: Lots of more stuff
    
    public static Handler noop() {
        return NOOP;
    }
    
    private static final Handler NOOP = GET().run(() -> {});
}