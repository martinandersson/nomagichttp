package alpha.nomagichttp.core.mediumtest.util;

import alpha.nomagichttp.handler.RequestHandler;

import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.text;

/**
 * Test request handlers.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class TestRequestHandlers {
    private TestRequestHandlers() {
        // Empty
    }
    
    /**
     * Creates a {@code POST} request handler which responds
     * {@code Request.body().isEmpty()} as the response body.
     * 
     * @return a request handler
     */
    public static RequestHandler respondIsBodyEmpty() {
        return POST().apply(req ->
                text(String.valueOf(req.body().isEmpty())));
    }
}