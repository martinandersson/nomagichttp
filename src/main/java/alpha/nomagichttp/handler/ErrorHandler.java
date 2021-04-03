package alpha.nomagichttp.handler;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.examples.RetryRequestOnError;
import alpha.nomagichttp.message.BadHeaderException;
import alpha.nomagichttp.message.ClosedPublisherException;
import alpha.nomagichttp.message.HttpVersionParseException;
import alpha.nomagichttp.message.HttpVersionTooNewException;
import alpha.nomagichttp.message.HttpVersionTooOldException;
import alpha.nomagichttp.message.MaxRequestHeadSizeExceededException;
import alpha.nomagichttp.message.MediaTypeParseException;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.RequestHeadParseException;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;
import alpha.nomagichttp.route.NoHandlerFoundException;
import alpha.nomagichttp.route.NoRouteFoundException;

import java.util.concurrent.CompletionException;

import static alpha.nomagichttp.message.Responses.badRequest;
import static alpha.nomagichttp.message.Responses.entityTooLarge;
import static alpha.nomagichttp.message.Responses.httpVersionNotSupported;
import static alpha.nomagichttp.message.Responses.internalServerError;
import static alpha.nomagichttp.message.Responses.notFound;
import static alpha.nomagichttp.message.Responses.notImplemented;
import static alpha.nomagichttp.message.Responses.upgradeRequired;
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
 * Many error handlers may be installed on the server. First to handle the error
 * breaks the call chain. Details follow later.<p>
 * 
 * The error handler must be thread-safe, as it may be called concurrently. As
 * far as the server is concerned, it does not need to implement 
 * {@code hashCode()} {@code equals(Object)}.<p>
 * 
 * The server will call error handlers only during the phase of the HTTP
 * exchange when there is a client waiting on a response and only if the channel
 * remains open for writing at the time of the error.<p>
 * 
 * Specifically for:<p>
 * 
 * 1) Exceptions occurring on the request thread from after the point when the
 * server has begun receiving and parsing a request message until when the
 * request handler invocation has returned.<p>
 * 
 * 2) Exceptions that completes exceptionally the {@code
 * CompletionStage<Response>} written to the {@link ClientChannel}.<p>
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
 * will be called in the same order they were added. First handler to not throw
 * an exception breaks the call chain. The {@link #DEFAULT default handler}
 * will be used if no other handler is configured.<p>
 * 
 * An error handler that is unwilling to handle the exception must re-throw the
 * same throwable instance which will then propagate to the next handler,
 * eventually reaching the default error handler if no other
 * application-provided handler resolved the error.<p>
 * 
 * If a handler throws a different throwable, then this is considered to be a
 * new error and the whole cycle is restarted.<p>
 * 
 * Super simple example:
 * <pre>{@code
 *     ErrorHandler eh = (throwable, channel, request, requestHandler) -> {
 *         try {
 *             throw throwable;
 *         } catch (ExpectedException e) {
 *             channel.write(myAlternativeResponse());
 *         } catch (AnotherExpectedException e) {
 *             channel.write(someOtherAlternativeResponse());
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
 * @see ErrorHandler#apply(Throwable, ClientChannel, Request, RequestHandler) 
 */
@FunctionalInterface
public interface ErrorHandler
{
    /**
     * Optionally handles an exception by producing an alternative response.<p>
     * 
     * The first two arguments ({@code Throwable} and {@code ClientChannel})
     * will always be non-null. The last two arguments ({@code Request} and
     * {@code RequestHandler}) may be null or non-null depending on how much
     * progress was made in the HTTP exchange before the error occurred.<p>
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
     * the error. For example, {@link NoRouteFoundException} provides the path
     * for which no route was found, which could potentially be used by the
     * application as a basis for a redirect.<p>
     * 
     * If the error which the server caught is a {@link CompletionException},
     * then the server will attempt to recursively unpack a non-null cause and
     * pass the cause to the error handler instead.
     * 
     * @param thr the error (never null)
     * @param ch client channel (never null)
     * @param req request object (may be null)
     * @param rh  request handler object (may be null)
     * 
     * @throws Throwable may be {@code thr} or a new one
     * 
     * @see ErrorHandler
     */
    void apply(Throwable thr, ClientChannel ch, Request req, RequestHandler rh) throws Throwable;
    
    /**
     * Is the default error handler used by the server if no other handler has
     * been provided or no error handler handled the error.<p>
     * 
     * The error will be dealt with accordingly:
     * 
     * <table class="striped">
     *   <caption style="display:none">Default Handlers</caption>
     *   <thead>
     *   <tr>
     *     <th scope="col">Exception Type</th>
     *     <th scope="col">Logged</th>
     *     <th scope="col">Response</th>
     *   </tr>
     *   </thead>
     *   <tbody>
     *   <tr>
     *     <th scope="row"> {@link RequestHeadParseException} </th>
     *     <td> No </td>
     *     <td> {@link Responses#badRequest()} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link HttpVersionParseException} </th>
     *     <td> No </td>
     *     <td> {@link Responses#badRequest()} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link BadHeaderException} </th>
     *     <td> No </td>
     *     <td> {@link Responses#badRequest()} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link HttpVersionTooOldException} </th>
     *     <td> No </td>
     *     <td> {@link Responses#upgradeRequired(String)} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link HttpVersionTooNewException} </th>
     *     <td> No </td>
     *     <td> {@link Responses#httpVersionNotSupported()} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link NoRouteFoundException} </th>
     *     <td> Yes </td>
     *     <td> {@link Responses#notFound()} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link MaxRequestHeadSizeExceededException} </th>
     *     <td> No </td>
     *     <td> {@link Responses#entityTooLarge()} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link NoHandlerFoundException} </th>
     *     <td> Yes </td>
     *     <td> {@link Responses#notImplemented()} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link MediaTypeParseException} <br>
     *                      If handler argument is null</th>
     *     <td> No </td>
     *     <td> {@link Responses#badRequest()} <br>
     *          Fault assumed to be the clients'.</td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link MediaTypeParseException} <br>
     *                      If handler argument is not null</th>
     *     <td> Yes </td>
     *     <td> {@link Responses#internalServerError()} <br>
     *          Fault assumed to be the request handlers'.</td>
     *   </tr>
     *   <tr>
     *     <th scope="row">{@link ClosedPublisherException} <br>
     *                     If message is "EOS"</th>
     *     <td> No </td>
     *     <td> No response, channel is closed. <br>
     *          This error signals the failure of a read operation due to client
     *          disconnect <i>and</i> at least one byte of data was received
     *          prior to the disconnect (if no bytes were received the error
     *          handler is never called; no data loss, no problem). Currently,
     *          however, there's no API support to retrieve the incomplete
     *          request. This might be added in the future.</td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> <i>{@code Everything else}</i> </th>
     *     <td> Yes </td>
     *     <td> {@link Responses#internalServerError()} </td>
     *   </tr>
     *   </tbody>
     * </table>
     * 
     * Please note that each of these responses will also close the client
     * channel (see {@link Response#mustCloseAfterWrite()}).
     */
    ErrorHandler DEFAULT = (thr, ch, req, rh) -> {
        final Response res;
        try {
            throw thr;
        } catch (RequestHeadParseException | HttpVersionParseException | BadHeaderException e) {
            res = badRequest();
        } catch (HttpVersionTooOldException e) {
            res = upgradeRequired(e.upgrade());
        } catch (HttpVersionTooNewException e) {
            res = httpVersionNotSupported();
        } catch (NoRouteFoundException e) {
            log(thr);
            res = notFound();
        } catch (MaxRequestHeadSizeExceededException e) {
            res = entityTooLarge();
        } catch (NoHandlerFoundException e) { // + AmbiguousNoHandlerFoundException
            log(thr);
            res = notImplemented();
        } catch (MediaTypeParseException e) {
            if (rh == null) {
                res = badRequest();
            } else {
                log(thr);
                res = internalServerError();
            }
        } catch (ClosedPublisherException e) {
            if ("EOS".equals(e.getMessage())) {
                ch.closeSafe();
                return;
            } else {
                log(thr);
                res = internalServerError();
            }
        } catch (Throwable unknown) {
            log(thr);
            res = internalServerError();
        }
        
        ch.write(res);
    };
    
    private static void log(Throwable thr) {
        System.getLogger(ErrorHandler.class.getPackageName())
                .log(ERROR, "Default error handler received:", thr);
    }
}