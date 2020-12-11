package alpha.nomagichttp.handler;

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
public final class RequestHandlers
{
    private RequestHandlers() {
        // Empty
    }
    
    /**
     * Builds a handler responding to method "GET".<p>
     * 
     * @return a builder positioned at the last step
     * 
     * @see RequestHandlers
     * @see RequestHandler.Builder
     */
    public static RequestHandler.Builder.LastStep GET() {
        return method("GET");
    }
    
    /**
     * Builds a handler responding to method "HEAD".<p>
     *
     * @return a builder positioned at the last step
     *
     * @see RequestHandlers
     * @see RequestHandler.Builder
     */
    public static RequestHandler.Builder.LastStep HEAD() {
        return method("HEAD");
    }
    
    /**
     * Builds a handler responding to method "POST".<p>
     *
     * @return a builder positioned at the last step
     *
     * @see RequestHandlers
     * @see RequestHandler.Builder
     */
    public static RequestHandler.Builder.LastStep POST() {
        return method("POST");
    }
    
    /**
     * Builds a handler responding to method "PUT".<p>
     *
     * @return a builder positioned at the last step
     *
     * @see RequestHandlers
     * @see RequestHandler.Builder
     */
    public static RequestHandler.Builder.LastStep PUT() {
        return method("PUT");
    }
    
    /**
     * Builds a handler responding to method "DELETE".<p>
     *
     * @return a builder positioned at the last step
     *
     * @see RequestHandlers
     * @see RequestHandler.Builder
     */
    public static RequestHandler.Builder.LastStep DELETE() {
        return method("DELETE");
    }
    
    private static RequestHandler.Builder.LastStep method(String method) {
        return RequestHandler.builder(method)
                .consumesNothingAndAll()
                .producesAll();
    }
    
    /**
     * Builds a handler responding to method "GET" that does nothing.<p>
     * 
     * Should probably only be useful for testing.
     *
     * @return a handler
     *
     * @see RequestHandlers
     * @see RequestHandler.Builder
     */
    public static RequestHandler noop() {
        return GET().run(() -> {});
    }
}