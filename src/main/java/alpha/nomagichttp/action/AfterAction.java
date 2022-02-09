package alpha.nomagichttp.action;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.ReceiverOfUniqueRequestObject;
import alpha.nomagichttp.event.ResponseSent;
import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;

import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;

/**
 * An action executed after a response to a valid request has been received by
 * the {@link ClientChannel} and before it attempts to be sent on the wire. The
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
 *   AfterAction setId = (req, rsp) -{@literal >}
 *           req.attributes().{@literal <}String{@literal >}getOptAny("my.saved.correlation-id")
 *              .or(() -{@literal >} req.headers().firstValue(X_CORRELATION_ID))
 *              .map(id -{@literal >} rsp.toBuilder().header(X_CORRELATION_ID, id).build())
 *              .orElse(rsp)
 *              .completedStage();
 *   server.after("/*", setId);
 * </pre>
 * 
 * If the action returns exceptionally, then the exception is passed to the
 * server's error handler(s) which is just the same as with before-actions and
 * request handlers. An after-action should probably never throw an exception,
 * as this would be highly subject to a never-ending loop: request handler
 * -{@literal >} channel -{@literal >} after-action -{@literal >} exception
 * handler -{@literal >} channel -{@literal >} after-action -{@literal >}
 * exception handler -{@literal >} channel -{@literal >} after-action ... (see
 * {@link Config#maxErrorRecoveryAttempts()})<p>
 * 
 * Returning {@code null} is the same as throwing a {@code
 * NullPointerException}.<p>
 * 
 * Similar to {@link BeforeAction}, the after-action is only called for
 * responses responding to a valid request. For example, if a request head fails
 * to parse and the error handler writes an alternative response, for that
 * response instance no after action will be called.<p>
 * 
 * An action that will be invoked for responses to all <i>valid</i> requests
 * hitting the server can be registered using the path "/*". If the purpose for
 * such an action is to gather metrics, consider instead tapping into all
 * responses sent out by the server regardless if the request was valid, by
 * subscribing to the {@link ResponseSent} event (see {@link
 * HttpServer#events()}).<p>
 * 
 * The action may be called concurrently and must be thread-safe. It may be
 * called by the server's request thread and so must not block. No argument
 * passed to the action will be {@code null}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
@FunctionalInterface
public interface AfterAction extends
        BiFunction<Request, Response, CompletionStage<Response>>,
        ReceiverOfUniqueRequestObject
{
    // Empty
}