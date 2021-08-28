package alpha.nomagichttp;

import alpha.nomagichttp.action.BeforeAction;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.route.Route;

/**
 * A marker interface documenting the functional receiver of a unique {@link
 * Request} object instance.<p>
 * 
 * The function receives a receiver-unique request object instance for the sole
 * reason that the receiver may have been associated with a unique path pattern,
 * which affects path parameters available in the {@link Request#target()}.<p>
 * 
 * For example, although request "/hello" matches {@link BeforeAction} "/:foo"
 * and {@link Route} "/:bar", the former will have to use the key "foo" when
 * retrieving the segment value from the request target and a {@link
 * RequestHandler} of the latter will have to use the key "bar". As another
 * example, suppose a before-action registers using the pattern "/*path" and
 * there's also a route "/hello/:path". For an inbound request "/hello/world",
 * the former's "path" parameter will map to the value "/hello/world" and the
 * route's request handler will observe the value "world" using the same key.<p>
 * 
 * A unique request instance per executed server resource eliminates bugs that
 * could otherwise have manifested themselves if the request object is accessed
 * asynchronously. E.g., a before-action which asynchronously logs a path
 * parameter from a saved reference of the request instance passed to the
 * action, will always observe the expected path parameter value even if the
 * invocation chain has already proceeded to the request handler.<p>
 * 
 * Rest assured that all other components of the request object is shared by all
 * request instances created throughout the HTTP exchange, most importantly the
 * request attributes and body. Therefore, changes to these structures propagate
 * across execution boundaries, such as setting attributes and consuming the
 * body bytes (which should be done only once!).
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public interface ReceiverOfUniqueRequestObject {
    // Empty
}