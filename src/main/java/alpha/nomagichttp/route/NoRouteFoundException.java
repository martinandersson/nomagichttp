package alpha.nomagichttp.route;

/**
 * TODO: Docs
 * 
 * @see RouteRegistry#lookup(String) 
 */
public class NoRouteFoundException extends RuntimeException {
    public NoRouteFoundException(String message) {
        super(message);
    }
}
