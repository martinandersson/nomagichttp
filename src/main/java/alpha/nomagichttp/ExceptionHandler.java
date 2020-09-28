package alpha.nomagichttp;

import alpha.nomagichttp.handler.Handler;
import alpha.nomagichttp.message.BadHeaderException;
import alpha.nomagichttp.message.BadMediaTypeSyntaxException;
import alpha.nomagichttp.message.MaxRequestHeadSizeExceededException;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.RequestHeadParseException;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;
import alpha.nomagichttp.route.AmbiguousNoHandlerFoundException;
import alpha.nomagichttp.route.NoHandlerFoundException;
import alpha.nomagichttp.route.NoRouteFoundException;
import alpha.nomagichttp.route.Route;

import java.util.concurrent.CompletionStage;

import static alpha.nomagichttp.message.Responses.badRequest;
import static alpha.nomagichttp.message.Responses.entityTooLarge;
import static alpha.nomagichttp.message.Responses.internalServerError;
import static alpha.nomagichttp.message.Responses.notFound;
import static alpha.nomagichttp.message.Responses.notImplemented;
import static java.lang.System.Logger.Level.ERROR;

/**
 * Handles exceptions originating from after the point where a client has begun
 * transmitting a request until the request handler invocation has returned.<p>
 * 
 * The exception handler produces a response which - due to an error - the
 * ordinary request handler could not provide.<p>
 * 
 * If no exception handler is configured on the server, the {@link #DEFAULT}
 * will be used.<p>
 * 
 * Exception handlers are called in the same order they were configured/added to
 * the server.<p>
 * 
 * An exception handler that is unfit or unwilling to handle a particular
 * exception must re-throw the same exception instance, in which case the server
 * will call the next exception handler, eventually reaching the {@code
 * DEFAULT}. If an exception handler returns {@code null} or throws a different
 * exception, then this is considered to be a new error and the whole cycle is
 * restarted.<p>
 * 
 * The server accepts a supplier of the exception handler. The supplier will be
 * called lazily upon the first invocation of the exception handler and the
 * handler instance returned from the supplier is cached throughout each unique
 * HTTP exchange. This means that the handler can safely keep state related to
 * the exchange such as a retry-counter.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see ServerConfig#maxErrorRecoveryAttempts() 
 */
@FunctionalInterface
public interface ExceptionHandler
{
    /**
     * Handles an exception by producing a client response.<p>
     * 
     * The first argument will always be non-null. The rest of the arguments may
     * be null or non-null depending on how much progress was made in the HTTP
     * exchange before the error occurred.<p>
     * 
     * For example, if {@code exc} is a {@link NoHandlerFoundException} then all
     * arguments to the left of {@code handler} will be non-null but {@code
     * handler} will be null (because it was never successfully resolved).<p>
     *
     * The final step of the exchange is for the server to invoke the request
     * handler, and so all arguments will be non-null for all errors thrown by
     * the request handler.<p>
     * 
     * If the original error is a {@code CompletionException}, then the server
     * will attempt to recursively unpack the cause which is then passed to the
     * exception handler.
     * 
     * @param exc the error (never null)
     * @param req request object (may be null)
     * @param route route object (may be null)
     * @param handler handler object (may be null)
     * 
     * @return a client response
     * 
     * @throws Throwable may be same {@code exc} or a new one
     * 
     * @see ExceptionHandler
     */
    CompletionStage<Response> apply(Throwable exc, Request req, Route route, Handler handler) throws Throwable;
    
    /**
     * Is the default exception handler used by the server if no other exception
     * handler has been provided or is applicable.<p>
     * 
     * The default exception handler will immediately log the exception, then
     * proceed to return a response according to the following table.
     *
     * <table class="striped">
     *   <thead>
     *   <tr>
     *     <th scope="col">Exception Type</th>
     *     <th scope="col">Response</th>
     *   </tr>
     *   </thead>
     *   <tbody>
     *   <tr>
     *     <th scope="row"> {@link BadHeaderException} </th>
     *     <td> {@link Responses#badRequest()} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link RequestHeadParseException} </th>
     *     <td> {@link Responses#badRequest()} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link NoRouteFoundException} </th>
     *     <td> {@link Responses#notFound()} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link MaxRequestHeadSizeExceededException} </th>
     *     <td> {@link Responses#entityTooLarge()} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link NoHandlerFoundException} </th>
     *     <td> {@link Responses#notImplemented()} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link AmbiguousNoHandlerFoundException} </th>
     *     <td> {@link Responses#notImplemented()} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link BadMediaTypeSyntaxException} </th>
     *     <td> If handler argument is null, then {@link Responses#badRequest()},
     *          otherwise {@link Responses#internalServerError()}</td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> <i>{@code Everything else}</i> </th>
     *     <td> {@link Responses#internalServerError()} </td>
     *   </tr>
     *   </tbody>
     * </table>
     * 
     * Please note that each of these responses will also close the client
     * channel (see {@link Response#mustCloseAfterWrite()}).
     */
    ExceptionHandler DEFAULT = (exc, req, route, handler) -> {
        System.getLogger(ExceptionHandler.class.getPackageName()).log(ERROR, exc);
        
        final Response res;
        try {
            throw exc;
        } catch (BadHeaderException | RequestHeadParseException e) {
            res = badRequest();
        } catch (NoRouteFoundException e) {
            res = notFound();
        } catch (MaxRequestHeadSizeExceededException e) {
            res = entityTooLarge();
        } catch (NoHandlerFoundException | AmbiguousNoHandlerFoundException e) {
            res = notImplemented();
        } catch (BadMediaTypeSyntaxException e) {
            res = handler == null ? badRequest() : internalServerError();
        } catch (Throwable t) {
            res = internalServerError();
        }
        
        return res.asCompletedStage();
    };
}