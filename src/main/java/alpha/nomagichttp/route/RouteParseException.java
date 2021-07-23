package alpha.nomagichttp.route;

/**
 * Thrown by {@link Route.Builder#builder(String)} if parsing a route fails.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class RouteParseException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    
    /**
     * Constructs this object.
     * 
     * @param cause passed as-is to {@link Throwable#Throwable(Throwable)}
     */
    public RouteParseException(Throwable cause) {
        super(cause);
    }
}