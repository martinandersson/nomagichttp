package alpha.nomagichttp.handler;

import alpha.nomagichttp.HttpConstants;

/**
 * Utility methods for building {@link RequestHandler}s.<p>
 * 
 * The handlers returned from this class are oblivious to inbound "Content-Type"
 * and "Accept" headers; any media type in the request is permissible. The only
 * thing that matters when determining what requests the handler can handle is
 * the request's resource-target (route) and HTTP method.<p>
 * 
 * For a more fine-grained control, use {@link RequestHandler#builder(String)}
 * or the static utility methods provided in the builder interface such as
 * {@link RequestHandler.Builder#GET()} and {@link
 * RequestHandler.Builder#POST()}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
// TODO: Replace with RequestHandler.GET(), POST(), et cetera
//       (RequestHandler made effectively mutable)
public final class RequestHandlers
{
    private RequestHandlers() {
        // Empty
    }
    
    /**
     * Builds a handler responding to method {@link HttpConstants.Method#GET
     * GET}.<p>
     * 
     * @return a builder positioned at the last step
     * 
     * @see RequestHandlers
     * @see RequestHandler.Builder
     */
    public static RequestHandler.Builder.LastStep GET() {
        return method(HttpConstants.Method.GET);
    }
    
    /**
     * Builds a handler responding to method {@link HttpConstants.Method#HEAD
     * HEAD}.<p>
     * 
     * @return a builder positioned at the last step
     *
     * @see RequestHandlers
     * @see RequestHandler.Builder
     */
    public static RequestHandler.Builder.LastStep HEAD() {
        return method(HttpConstants.Method.HEAD);
    }
    
    /**
     * Builds a handler responding to method {@link HttpConstants.Method#POST
     * POST}.<p>
     * 
     * @return a builder positioned at the last step
     *
     * @see RequestHandlers
     * @see RequestHandler.Builder
     */
    public static RequestHandler.Builder.LastStep POST() {
        return method(HttpConstants.Method.POST);
    }
    
    /**
     * Builds a handler responding to method {@link HttpConstants.Method#PUT
     * PUT}.<p>
     * 
     * @return a builder positioned at the last step
     * 
     * @see RequestHandlers
     * @see RequestHandler.Builder
     */
    public static RequestHandler.Builder.LastStep PUT() {
        return method(HttpConstants.Method.PUT);
    }
    
    /**
     * Builds a handler responding to method {@link HttpConstants.Method#DELETE
     * DELETE}.<p>
     * 
     * @return a builder positioned at the last step
     * 
     * @see RequestHandlers
     * @see RequestHandler.Builder
     */
    public static RequestHandler.Builder.LastStep DELETE() {
        return method(HttpConstants.Method.DELETE);
    }
    
    private static RequestHandler.Builder.LastStep method(String method) {
        return RequestHandler.builder(method)
                .consumesNothingAndAll()
                .producesAll();
    }
}