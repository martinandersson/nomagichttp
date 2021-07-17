package alpha.nomagichttp.route;

import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.message.MediaType;

/**
 * Thrown by {@link Route#lookup(String, MediaType, MediaType[])} if the
 * requested HTTP method is not allowed - or in other words, no request handler
 * has been added that supports the method.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @see ErrorHandler#DEFAULT
 */
public class MethodNotAllowedException extends NoHandlerResolvedException {
    private static final long serialVersionUID = 1L;
    
    /**
     * Constructs this object.
     * 
     * @param method      of request
     * @param route       matched
     * @param contentType of request
     * @param accepts     of request
     */
    public MethodNotAllowedException(
            String method, Route route, MediaType contentType, MediaType[] accepts)
    {
        super("No handler found for method token \"" + method + "\".",
                method, route, contentType, accepts);
    }
}