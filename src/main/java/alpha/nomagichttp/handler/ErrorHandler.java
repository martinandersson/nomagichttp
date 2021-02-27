package alpha.nomagichttp.handler;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.examples.RetryRequestOnError;
import alpha.nomagichttp.message.BadHeaderException;
import alpha.nomagichttp.message.BadMediaTypeSyntaxException;
import alpha.nomagichttp.message.MaxRequestHeadSizeExceededException;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.RequestHeadParseException;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;
import alpha.nomagichttp.route.NoHandlerFoundException;
import alpha.nomagichttp.route.NoRouteFoundException;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static alpha.nomagichttp.message.Responses.badRequest;
import static alpha.nomagichttp.message.Responses.entityTooLarge;
import static alpha.nomagichttp.message.Responses.internalServerError;
import static alpha.nomagichttp.message.Responses.notFound;
import static alpha.nomagichttp.message.Responses.notImplemented;
import static java.lang.System.Logger.Level.ERROR;

/**
 * Handles a {@code Throwable} by translating it into an alternative response.<p>
 * 
 * One use case could be to retry a new execution of the request handler on
 * known and expected errors. Another use case could be to customize the
 * server's default error responses, for example by translating a {@code
 * NoRouteFoundException} into an application-specific "404 Not Found"
 * response.<p>
 * 
 * The server will call error handlers only during the phase of the HTTP
 * exchange when there is a client waiting on a response which the ordinary
 * request handler could not successfully provide and only if the channel
 * remains open at the time of the error.<p>
 * 
 * Specifically for:<p>
 * 
 * 1) Exceptions occurring on the request thread from after the point when the
 * server has begun receiving and parsing a request message until when the
 * request handler invocation has returned.<p>
 * 
 * 2) Exceptions that completes exceptionally the response {@code
 * CompletionStage} returned from the request handler.<p>
 * 
 * 3) Exceptions signalled to the server's {@code Flow.Subscriber} of the {@link
 * Response#body() response body} - if and only if - the body publisher has not
 * yet published any bytebuffers before the error was signalled. It doesn't make
 * much sense trying to recover the situation after the point where a response
 * has already begun transmitting back to the client.<p>
 * 
 * The server will <strong>not</strong> call error handlers for errors that are
 * not directly involved in the HTTP exchange or for errors that occur
 * asynchronously in another thread than the request thread or for any other
 * errors when there's already an avenue in place for the exception management.
 * For example, low-level exceptions related to channel management and error
 * signals raised through the {@link Request.Body} API (all methods of which
 * either return a {@code CompletionStage} or accepts a {@code
 * Flow.Subscriber}.<p>
 * 
 * For errors caught but not propagated to an error handler, the server's
 * strategy is usually to log the error and immediately close the client's
 * channel according to the procedure documented in {@link
 * Response#mustCloseAfterWrite()}.<p>
 * 
 * Any number of error handlers can be configured. If many are configured, they
 * will be called in the same order they were added. First handler to produce a
 * {@code Response} breaks the call chain. The {@link #DEFAULT} will be used if
 * no handler can handle the error or no handler is configured.<p>
 * 
 * An error handler that is unwilling to handle the exception must re-throw the
 * same throwable instance which will then propagate to the next handler. If a
 * handler throws a different throwable, then this is considered to be a new
 * error and the whole cycle is restarted.<p>
 * 
 * Super simple example:
 * <pre>{@code
 *     ErrorHandler eh = (throwable, request, requestHandler) -> {
 *         try {
 *             throw throwable;
 *         } catch (ExpectedException e) {
 *             return alternativeResponse();
 *         } catch (AnotherExpectedException e) {
 *             return anotherAlternativeResponse();
 *         }
 *         // else automagically re-thrown and propagated throughout the chain
 *     };
 * }</pre>
 * 
 * If there is a request available when the error handler is called, then the
 * {@link Request#attributes() request attributes} is a good place to store
 * state that needs to be passed between handler invocations, such as an error
 * retry counter (see example {@link RetryRequestOnError}).
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see HttpServer.Config#maxErrorRecoveryAttempts() 
 * @see ErrorHandler#apply(Throwable, Request, RequestHandler) 
 */
@FunctionalInterface
public interface ErrorHandler
{
    /**
     * Handles an exception by producing a client response.<p>
     * 
     * The first argument ({@code Throwable}) will always be non-null. The
     * succeeding two arguments ({@code Request} and {@code RequestHandler})
     * may be null or non-null depending on how much progress was made in the
     * HTTP exchange before the error occurred.<p>
     * 
     * A little bit simplified; the server's procedure is to always first build
     * a request object, which is then used to invoke the request handler
     * with.<p>
     * 
     * So, if the request argument is null, then the request handler argument
     * will absolutely also be null (the server never got so far as to find
     * and/or invoke the request handler).<p>
     * 
     * If the request argument is not null, then the request handler argument
     * may or may not be null. If the request handler is not null, then the
     * "fault" of the error is most likely the request handlers' since the very
     * next thing the server do after having found the request handler is to
     * call it.<p>
     * 
     * However, the true nature of the error can only be determined by looking
     * into the error object itself, which also might reveal what to expect from
     * the succeeding arguments. For example, if {@code thr} is a {@link
     * NoHandlerFoundException}, then the request object was built and will not
     * be null, but since the request handler wasn't found then obviously the
     * request handler argument is going to be null.<p>
     * 
     * It is a design goal of the NoMagicHTTP library to have each exception
     * type provide whatever API necessary to investigate and possibly resolve
     * the error. For example, {@code NoRouteFoundException} provides the path
     * for which no route was found, which could potentially be used by the
     * application as a basis for a redirect.<p>
     * 
     * If the error which the server caught is a {@link CompletionException},
     * then the server will attempt to recursively unpack a non-null cause and
     * pass the cause to the error handler instead.
     * 
     * @param thr the error (never null)
     * @param req request object (may be null)
     * @param rh  request handler object (may be null)
     * 
     * @return a client response
     * 
     * @throws Throwable may be {@code thr} or a new one
     * 
     * @see ErrorHandler
     */
    CompletionStage<Response> apply(Throwable thr, Request req, RequestHandler rh) throws Throwable;
    
    /**
     * Is the default error handler used by the server if no other handler has
     * been provided or no error handler handled the error.<p>
     * 
     * The default error handler will immediately log the exception, then
     * proceed to return a response according to the following table.
     * 
     * <table class="striped">
     *   <caption style="display:none">Default Handlers</caption>
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
     *     <th scope="row"> {@link BadMediaTypeSyntaxException} </th>
     *     <td> If handler argument is null, then {@link Responses#badRequest()}
     *          (fault assumed to be the clients'), otherwise {@link
     *          Responses#internalServerError()} (fault assumed to be the
     *          request handlers')</td>
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
    ErrorHandler DEFAULT = (thr, req, rh) -> {
        System.getLogger(ErrorHandler.class.getPackageName())
                .log(ERROR, "Default error handler received:", thr);
        
        final Response res;
        try {
            throw thr;
        } catch (BadHeaderException | RequestHeadParseException e) {
            res = badRequest();
        } catch (NoRouteFoundException e) { // + AmbiguousRouteCollisionException
            res = notFound();
        } catch (MaxRequestHeadSizeExceededException e) {
            res = entityTooLarge();
        } catch (NoHandlerFoundException e) { // + AmbiguousNoHandlerFoundException
            res = notImplemented();
        } catch (BadMediaTypeSyntaxException e) {
            res = rh == null ? badRequest() : internalServerError();
        } catch (Throwable unhandledDefaultCase) {
            res = internalServerError();
        }
        
        return res.completedStage();
    };
}