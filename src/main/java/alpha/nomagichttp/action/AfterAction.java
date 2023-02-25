package alpha.nomagichttp.action;

import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;

import java.util.function.BiFunction;

/**
 * Is an action executed after the channel has received a response.<p>
 * 
 * More specifically, the action is executed just before the
 * {@link ClientChannel} attempts to send a valid response on the wire. The
 * action may return the same response instance unmodified, or produce another
 * alternative response.<p>
 * 
 * To build on the example provided in {@link ActionRegistry}, here is one way
 * to propagate a correlation id from the request headers- or attributes,
 * falling back to no response modification if the value is not present:
 * <pre>
 *   import static alpha.nomagichttp.HttpConstants.HeaderName.X_CORRELATION_ID;
 *   ...
 *   
 *   HttpServer server = ...
 *   AfterAction trySetId = (req, rsp) -{@literal >}
 *           req.attributes().{@literal <}String{@literal >}getOptAny("my.saved.correlation-id")
 *              .or(() -{@literal >} req.headers().firstValue(X_CORRELATION_ID))
 *              .map(id -{@literal >} rsp.toBuilder().header(X_CORRELATION_ID, id).build())
 *              .orElse(rsp);
 *   // Propagate for all exchanges
 *   server.after("/*", trySetId);
 * </pre>
 * 
 * An after-action must never throw an exception. It wouldn't make sense to run
 * such an error though the error handlers as doing so would be subject to a
 * never-ending loop.<p>
 * 
 * Returning {@code null} is the same as throwing a {@code
 * NullPointerException}.<p>
 * 
 * Similar to {@link BeforeAction}, the after-action is only called for
 * responses responding to a valid request. For example, if a request head fails
 * to parse and the error handler writes an alternative response, for that
 * response instance no after action will be called. Both arguments provided to
 * the after-action will never be {@code null}.<p>
 * 
 * The action may be called concurrently and must be thread-safe.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
@FunctionalInterface
public interface AfterAction extends BiFunction<Request, Response, Response> {
    // Empty
}