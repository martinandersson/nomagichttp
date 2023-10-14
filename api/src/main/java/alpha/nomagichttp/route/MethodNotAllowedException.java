package alpha.nomagichttp.route;

import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;

import java.util.Collection;

import static alpha.nomagichttp.message.Responses.methodNotAllowed;

/**
 * Thrown by {@link Route#lookup(String, MediaType, Collection)} if the
 * requested HTTP method is not allowed - or in other words, no request handler
 * has been added that supports the method.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class MethodNotAllowedException extends NoHandlerResolvedException
{
    private static final long serialVersionUID = 1L;
    
    /**
     * Constructs this object.
     * 
     * @param route       matched target for the lookup operation
     * @param method      of request (first argument to lookup)
     * @param contentType of request (second argument to lookup)
     * @param accepts     of request (third argument to lookup)
     */
    public MethodNotAllowedException(
            Route route, String method, MediaType contentType, Collection<MediaType> accepts)
    {
        super("No handler found for method token \"" + method + "\".",
                route, method, contentType, accepts);
    }
    
    /**
     * Returns {@link Responses#methodNotAllowed()}.
     * 
     * @return see Javadoc
     */
    @Override
    public Response getResponse() {
        return methodNotAllowed();
    }
}