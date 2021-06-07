package alpha.nomagichttp.testutil;

import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.Responses;
import alpha.nomagichttp.route.Route;

import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.text;

/**
 * All test routes map to server root "/".
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class TestRoutes {
    private TestRoutes() {
        // Empty
    }
    
    /**
     * Expect {@code POST} requests on "/" and respond a text-body with the
     * value of {@code request.body().isEmpty()}.
     * 
     * @return a route
     */
    public static Route respondIsBodyEmpty() {
        return root(POST().apply(req ->
                text(String.valueOf(req.body().isEmpty())).completedStage()));
    }
    
    /**
     * Expect {@code POST} requests on "/" and respond a text-body with the
     * value of {@code request.body().toText()}.
     * 
     * @return a route
     */
    public static Route respondRequestBody() {
        return root(POST().apply(req ->
                req.body().toText().thenApply(Responses::text)));
    }
    
    private static Route root(RequestHandler rh) {
        return Route.builder("/").handler(rh).build();
    }
}