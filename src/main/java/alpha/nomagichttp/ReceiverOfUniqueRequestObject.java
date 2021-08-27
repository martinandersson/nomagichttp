package alpha.nomagichttp;

import alpha.nomagichttp.action.BeforeAction;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.route.Route;

/**
 * A marker interface documenting the receiver of a unique {@link Request}
 * object instance.<p>
 * 
 * The receiver receives a unique request object instance for the sole reason
 * that the receiver may have been associated with a unique path pattern, which
 * affects path parameters.<p>
 * 
 * For example, although request "/hello" matches {@link BeforeAction} "/:foo"
 * and {@link Route} "/:bar", the former will have to use the key "foo" when
 * retrieving the segment value from {@link Request#target()} and a {@link
 * RequestHandler} of the latter will have to use the key "bar". As another
 * example, suppose a before-action registers using the pattern "/*path" and
 * there's also a route "/hello/:path". For an inbound request "/hello/world",
 * the former's "path" parameter will map to the value "/hello/world" and the
 * route's request handler will observe the value "world" using the same key.<p>
 * 
 * All other components of the request object will be shared throughout the HTTP
 * exchange, most importantly the request attributes and body. Therefore,
 * changes to these structures propagate across execution boundaries, such as
 * setting attributes and consuming the body bytes (which should only be done
 * once!).
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public interface ReceiverOfUniqueRequestObject {
    // Empty
}