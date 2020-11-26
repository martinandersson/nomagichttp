package alpha.nomagichttp.handler;

import alpha.nomagichttp.message.MediaType;

/**
 * Utility methods for building the default handler implementation with
 * non-restrictive default values for media types; all handlers returned by this
 * class consumes {@link MediaType#NOTHING_AND_ALL} and produces
 * {@link MediaType#ALL}.<p>
 * 
 * Another way to put it is that the handlers produced by this class does not
 * care about the presence or value of the "Content-Type" and "Accept" header in
 * the inbound request. They accept all inbound media types and can produce
 * anything in return. Use {@link HandlerBuilder} directly for a more
 * fine-grained control.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see RequestHandler
 */
// TODO: Rename to RequestHandlers
public final class Handlers
{
    private Handlers() {
        // Empty
    }
    
    /**
     * Utility method to construct a "GET" handler.<p>
     * 
     * The returned HandlerBuilder will be positioned at the last step where the
     * logic of the handler must be specified.
     * 
     * @return an almost complete HandlerBuilder
     * 
     * @see Handlers
     * @see RequestHandler.Builder
     */
    public static RequestHandler.Builder.LastStep GET() {
        return method("GET");
    }
    
    /**
     * Utility method to construct a "POST" handler.<p>
     *
     * The returned HandlerBuilder will be positioned at the last step where the
     * logic of the handler must be specified.
     *
     * @return an almost complete HandlerBuilder
     *
     * @see Handlers
     * @see RequestHandler.Builder
     */
    public static RequestHandler.Builder.LastStep POST() {
        return method("POST");
    }
    
    /**
     * Utility method to construct a "PUT" handler.<p>
     *
     * The returned HandlerBuilder will be positioned at the last step where the
     * logic of the handler must be specified.
     *
     * @return an almost complete HandlerBuilder
     *
     * @see Handlers
     * @see RequestHandler.Builder
     */
    public static RequestHandler.Builder.LastStep PUT() {
        return method("PUT");
    }
    
    public static RequestHandler.Builder.LastStep method(String method) {
        return new DefaultRequestHandler.Builder(method)
                .consumesNothingAndAll()
                .producesAll();
    }
    
    public static RequestHandler noop() {
        return NOOP;
    }
    
    private static final RequestHandler NOOP = GET().run(() -> {});
}