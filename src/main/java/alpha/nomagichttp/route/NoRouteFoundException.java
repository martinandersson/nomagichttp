package alpha.nomagichttp.route;

import static java.util.Objects.requireNonNull;

/**
 * TODO: Docs
 * 
 * @see RouteRegistry#lookup(String) 
 */
public class NoRouteFoundException extends RuntimeException
{
    private final String rt;
    
    public NoRouteFoundException(String requestTarget) {
        super("No route matches this request-target: " + requestTarget);
        rt = requireNonNull(requestTarget);
    }
    
    /**
     * Returns the request target for which no configured {@link Route} was found.
     * 
     * @return the request target (never {@code null})
     */
    public String requestTarget() {
        return rt;
    }
}
