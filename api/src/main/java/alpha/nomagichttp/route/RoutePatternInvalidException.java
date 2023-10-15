package alpha.nomagichttp.route;

import java.io.Serial;

/**
 * Thrown by {@link Route.Builder#builder(String)} if a route pattern is invalid.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class RoutePatternInvalidException extends RuntimeException
{
    @Serial
    private static final long serialVersionUID = 1L;
    
    /**
     * Constructs this object.
     * 
     * @param cause passed as-is to {@link Throwable#Throwable(Throwable)}
     */
    public RoutePatternInvalidException(Throwable cause) {
        super(cause);
    }
}