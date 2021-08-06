package alpha.nomagichttp.action;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;

import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;

/**
 * An action executed after a response has been received by the {@link
 * ClientChannel} and before it attempts to be sent on the wire. The action may
 * return the same response instance unmodified, or produce another alternative
 * response.<p>
 * 
 * To build on the example provided in {@link ActionRegistry}, here is one way
 * to propagate a correlation id from the request- headers or attributes,
 * falling back to no response modification if the value is not present:
 * <pre>
 *   import static alpha.nomagichttp.HttpConstants.HeaderKey.X_CORRELATION_ID;
 *   ...
 *   
 *   HttpServer server = ...
 *   AfterAction setId = (request, responseStage) -{@literal >} responseStage.thenApply(rsp -{@literal >}
 *           request.attributes().{@literal <}String{@literal >}getOptAny("my.saved.correlation-id")
 *                  .or(() -{@literal >} request.headers().firstValue(X_CORRELATION_ID))
 *                  .map(id -{@literal >} rsp.toBuilder().header(X_CORRELATION_ID, id).build())
 *                  .orElse(rsp));
 *   server.after("/*", setId);
 * </pre>
 * 
 * If the action returns exceptionally, then the exception is passed to the
 * server's error handler(s) which is just the same as with before-actions and
 * request handlers. An after-action should probably never throw an exception,
 * as this would be highly subject to an ever-ending loop: request handler ->
 * channel -> after-action -> exception handler -> channel -> after-action ->
 * exception handler -> channel -> after-action ... (see {@link
 * Config#maxErrorRecoveryAttempts()})<p>
 * 
 * Returning {@code null} is the same as throwing a {@code
 * NullPointerException}.<p>
 * 
 * The action may be called concurrently and must be thread-safe. It may be
 * called by the server's request thread and so must not block.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @see ActionRegistry
 */
@FunctionalInterface
public interface AfterAction extends
        BiFunction<Request, CompletionStage<Response>, CompletionStage<Response>>
{
    // Empty
}