package alpha.nomagichttp.handler;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.ReceiverOfUniqueRequestObject;
import alpha.nomagichttp.action.AfterAction;
import alpha.nomagichttp.action.BeforeAction;
import alpha.nomagichttp.message.BadHeaderException;
import alpha.nomagichttp.message.BadRequestException;
import alpha.nomagichttp.message.DecoderException;
import alpha.nomagichttp.message.HeaderParseException;
import alpha.nomagichttp.message.HttpVersionParseException;
import alpha.nomagichttp.message.HttpVersionTooNewException;
import alpha.nomagichttp.message.HttpVersionTooOldException;
import alpha.nomagichttp.message.IllegalRequestBodyException;
import alpha.nomagichttp.message.IllegalResponseBodyException;
import alpha.nomagichttp.message.MaxRequestHeadSizeExceededException;
import alpha.nomagichttp.message.MediaTypeParseException;
import alpha.nomagichttp.message.ReadTimeoutException;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.RequestLineParseException;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.ResponseTimeoutException;
import alpha.nomagichttp.message.Responses;
import alpha.nomagichttp.route.AmbiguousHandlerException;
import alpha.nomagichttp.route.MediaTypeNotAcceptedException;
import alpha.nomagichttp.route.MediaTypeUnsupportedException;
import alpha.nomagichttp.route.MethodNotAllowedException;
import alpha.nomagichttp.route.NoRouteFoundException;

import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

import static alpha.nomagichttp.HttpConstants.HeaderName.ALLOW;
import static alpha.nomagichttp.HttpConstants.Method.OPTIONS;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.handler.ResponseRejectedException.Reason;
import static alpha.nomagichttp.handler.ResponseRejectedException.Reason.PROTOCOL_NOT_SUPPORTED;
import static alpha.nomagichttp.message.Responses.badRequest;
import static alpha.nomagichttp.message.Responses.entityTooLarge;
import static alpha.nomagichttp.message.Responses.httpVersionNotSupported;
import static alpha.nomagichttp.message.Responses.internalServerError;
import static alpha.nomagichttp.message.Responses.methodNotAllowed;
import static alpha.nomagichttp.message.Responses.noContent;
import static alpha.nomagichttp.message.Responses.notAcceptable;
import static alpha.nomagichttp.message.Responses.notFound;
import static alpha.nomagichttp.message.Responses.requestTimeout;
import static alpha.nomagichttp.message.Responses.serviceUnavailable;
import static alpha.nomagichttp.message.Responses.unsupportedMediaType;
import static alpha.nomagichttp.message.Responses.upgradeRequired;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Stream.concat;
import static java.util.stream.Stream.of;

/**
 * Handles a {@code Throwable}, presumably by translating it into a response.<p>
 * 
 * Error handler(s) should only be used for generic and server-global errors.
 * Another use case could be to customize the server's default error
 * responses.<p>
 * 
 * The server will call error handlers only during an active HTTP exchange and
 * only if the channel remains open for writing at the time of the error. The
 * purpose of error handlers is to be able to cater the client with a response
 * even in the event of failure.<p>
 * 
 * Specifically, the error handler may be called for:<p>
 * 
 * 1) Exceptions occurring while receiving and parsing a request head.<p>
 * 
 * 2) Exceptions from the execution of {@link BeforeAction}s, {@link
 * RequestHandler}s and {@link AfterAction}s.<p>
 * 
 * 3) Exceptions that complete exceptionally the {@code
 * CompletionStage<Response>} given to the {@link
 * ClientChannel#write(Response) ClientChannel} and those returned from an
 * {@link AfterAction}, but only if a final response has not yet been sent.<p>
 * 
 * 4) Exceptions signalled to the server's {@code Flow.Subscriber} of the {@link
 * Response#body()} but only if the body publisher has not yet published any
 * bytebuffers before the error was signalled. It doesn't make much sense trying
 * to recover the situation after the point where a response has already begun
 * transmitting back to the client.<p>
 * 
 * The server will <strong>not</strong> call error handlers for errors that are
 * not directly involved in the HTTP exchange or for errors that occur
 * asynchronously in another thread than the request thread or for any other
 * errors when there's already an avenue in place for exception handling. For
 * example, low-level exceptions related to channel management and error signals
 * raised through the {@link Request.Body} API (all methods of which either
 * return a {@code CompletionStage} or accepts a {@code Flow.Subscriber}.<p>
 * 
 * For errors caught but not propagated to an error handler, the server's
 * strategy is usually to log the error and immediately close the client's
 * channel.<p>
 * 
 * Any number of error handlers can be configured. If many are configured, they
 * will be called in the same order they were added. First handler to return
 * normally - i.e., first handler to <i>not</i> throw an exception - breaks the
 * call chain. The {@link #DEFAULT default handler} will be used if no other
 * handler is configured.<p>
 * 
 * An error handler that is unwilling to handle the exception must re-throw the
 * same throwable instance which will then propagate to the next handler,
 * eventually reaching the default handler.<p>
 * 
 * If a handler throws a different instance, then this is considered to be a new
 * error and the whole cycle is restarted.<p>
 * 
 * This design was deliberately crafted to enable writing error handlers using
 * Java's standard try-catch block:
 * <pre>
 *     ErrorHandler eh = (throwable, channel, request) -{@literal >} {
 *         try {
 *             throw throwable;
 *         } catch (ExpectedException e) {
 *             channel.{@link ClientChannel#writeFirst(Response) writeFirst}(myAlternativeResponse());
 *             // normal return; breaks the call chain
 *         } catch (AnotherExpectedException e) {
 *             channel.writeFirst(someOtherAlternativeResponse());
 *             // normal return; breaks the call chain
 *         }
 *         // else not handled by this handler; propagates throughout the chain
 *     };
 * </pre>
 * 
 * If there is a request available when the error handler is called, then {@link
 * Request#attributes()} is a good place to store state that needs to be passed
 * between handler invocations.<p>
 * 
 * The error handler must be thread-safe, as it may be called concurrently. As
 * far as the server is concerned, it does not need to implement 
 * {@code hashCode()} and {@code equals(Object)}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Config#maxErrorRecoveryAttempts() 
 * @see ErrorHandler#apply(Throwable, ClientChannel, Request)
 */
@FunctionalInterface
public interface ErrorHandler
{
    /**
     * Optionally handles an exception.<p>
     * 
     * The {@code Throwable} and {@code ClientChannel} arguments will always be
     * non-null. The {@code Request} object may be null depending on how much
     * progress was made in the HTTP exchange before the error occurred. For
     * example, if the error is a {@link RequestLineParseException}, then the
     * request object will obviously not be present.<p>
     * 
     * If the error which the server caught is a {@link CompletionException},
     * then the server will attempt to recursively unpack a non-null cause and
     * pass the cause to the error handler instead.<p>
     * 
     * The {@link ErrorHandler} interface does not extend {@link
     * ReceiverOfUniqueRequestObject}. The error handler will receive the last
     * request object created within the HTTP exchange, if available. I.e. the
     * request's path parameters' keys and values is dependent on whichever
     * entity was invoked last prior to the crash; generally speaking, they are
     * nondeterministic and unsafe to access.
     * 
     * @param thr the error (never null)
     * @param ch client channel (never null)
     * @param req request object (may be null)
     * 
     * @throws Throwable may be {@code thr} or a new one
     * 
     * @see ErrorHandler
     */
    void apply(Throwable thr, ClientChannel ch, Request req) throws Throwable;
    
    /**
     * Is the default error handler used by the server if no other
     * application-provided handler handled the error.<p>
     * 
     * The error will be dealt with accordingly:
     * 
     * <table class="striped">
     *   <caption style="display:none">Default Handlers</caption>
     *   <thead>
     *   <tr>
     *     <th scope="col">Exception Type</th>
     *     <th scope="col">Condition(s)</th>
     *     <th scope="col">Logged</th>
     *     <th scope="col">Response</th>
     *   </tr>
     *   </thead>
     *   <tbody>
     *   <tr>
     *     <th scope="row"> {@link RequestLineParseException} </th>
     *     <td> None </td>
     *     <td> No </td>
     *     <td> {@link Responses#badRequest()} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link HeaderParseException} </th>
     *     <td> None </td>
     *     <td> No </td>
     *     <td> {@link Responses#badRequest()} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link HttpVersionParseException} </th>
     *     <td> None </td>
     *     <td> No </td>
     *     <td> {@link Responses#badRequest()} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link HttpVersionTooOldException} </th>
     *     <td> None </td>
     *     <td> No </td>
     *     <td> {@link Responses#upgradeRequired(String)} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link HttpVersionTooNewException} </th>
     *     <td> None </td>
     *     <td> No </td>
     *     <td> {@link Responses#httpVersionNotSupported()} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link BadHeaderException} </th>
     *     <td> None </td>
     *     <td> No </td>
     *     <td> {@link Responses#badRequest()} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link BadRequestException} </th>
     *     <td> None </td>
     *     <td> No </td>
     *     <td> {@link Responses#badRequest()} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link MaxRequestHeadSizeExceededException} </th>
     *     <td> None </td>
     *     <td> Yes </td>
     *     <td> {@link Responses#entityTooLarge()} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link NoRouteFoundException} </th>
     *     <td> None </td>
     *     <td> Yes </td>
     *     <td> {@link Responses#notFound()} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link MethodNotAllowedException} </th>
     *     <td> HTTP method is {@value HttpConstants.Method#OPTIONS} and
     *          {@link Config#implementMissingOptions()} returns {@code true}</td>
     *     <td> No </td>
     *     <td> {@link Responses#noContent()} with the header
     *          {@value HttpConstants.HeaderName#ALLOW} populated.</td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link MethodNotAllowedException} </th>
     *     <td> HTTP method is not {@value HttpConstants.Method#OPTIONS} or
     *          {@link Config#implementMissingOptions()} returns {@code false}</td>
     *     <td> Yes </td>
     *     <td> {@link Responses#methodNotAllowed()} with the header
     *          {@value HttpConstants.HeaderName#ALLOW} populated.</td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link MediaTypeParseException} </th>
     *     <td> None </td>
     *     <td> Yes </td>
     *     <td> {@link Responses#internalServerError()} <br>
     *          Fault assumed to be the applications'.</td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link MediaTypeNotAcceptedException} </th>
     *     <td> None </td>
     *     <td> Yes </td>
     *     <td> {@link Responses#notAcceptable()} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link MediaTypeUnsupportedException} </th>
     *     <td> None </td>
     *     <td> Yes </td>
     *     <td> {@link Responses#unsupportedMediaType()} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link AmbiguousHandlerException} </th>
     *     <td> None </td>
     *     <td> Yes </td>
     *     <td> {@link Responses#internalServerError()} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link DecoderException} </th>
     *     <td> None </td>
     *     <td> No </td>
     *     <td> {@link Responses#badRequest()} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link IllegalRequestBodyException} </th>
     *     <td> None </td>
     *     <td> No </td>
     *     <td> {@link Responses#badRequest()} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link IllegalResponseBodyException} </th>
     *     <td> None </td>
     *     <td> Yes </td>
     *     <td> {@link Responses#internalServerError()} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row">{@link ResponseRejectedException} </th>
     *     <td> Response.{@link Response#isInformational() isInformational()}, and <br>
     *          rejected reason is {@link Reason#PROTOCOL_NOT_SUPPORTED
     *          PROTOCOL_NOT_SUPPORTED}, and <br>
     *          HTTP version is {@literal <} 1.1, and <br>
     *          {@link Config#ignoreRejectedInformational()
     *          ignoreRejectedInformational()} is {@code true}</td>
     *     <td> No </td>
     *     <td> No response, the failed interim response is ignored. </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link ReadTimeoutException} </th>
     *     <td> None </td>
     *     <td> No </td>
     *     <td> {@link Responses#requestTimeout()}</td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link ResponseTimeoutException} </th>
     *     <td> None </td>
     *     <td> Yes </td>
     *     <td> First shutdown input stream, then
     *          {@link Responses#serviceUnavailable()}.</td>
     *   </tr>
     *   <tr>
     *     <th scope="row">{@link EndOfStreamException} </th>
     *     <td> None </td>
     *     <td> No </td>
     *     <td> No response, closes the channel. <br>
     *          This error signals the failure of a read operation due to client
     *          disconnect <i>and</i> at least one byte of data was received
     *          prior to the disconnect (if no bytes were received the error
     *          handler is never called; no data loss, no problem). Currently,
     *          however, there's no API support to retrieve the incomplete
     *          request.</td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> <i>{@code Everything else}</i> </th>
     *     <td> None </td>
     *     <td> Yes </td>
     *     <td> {@link Responses#internalServerError()} </td>
     *   </tr>
     *   </tbody>
     * </table>
     */
    ErrorHandler DEFAULT = (thr, ch, req) -> {
        final Response res;
        try {
            throw thr;
        } catch (RequestLineParseException   |
                 HeaderParseException        |
                 HttpVersionParseException   |
                 BadHeaderException          |
                 BadRequestException         |
                 IllegalRequestBodyException |
                 DecoderException e) {
            res = badRequest();
        }  catch (HttpVersionTooOldException e) {
            res = upgradeRequired(e.getUpgrade());
        } catch (HttpVersionTooNewException e) {
            res = httpVersionNotSupported();
        } catch (MaxRequestHeadSizeExceededException e) {
            log(thr);
            res = entityTooLarge();
        } catch (NoRouteFoundException e) {
            log(thr);
            res = notFound();
        } catch (MethodNotAllowedException e) {
            Response status = methodNotAllowed();
            Stream<String> allow = e.getRoute().supportedMethods();
            if (req.method().equals(OPTIONS) && ch.getServer().getConfig().implementMissingOptions()) {
                status = noContent();
                // Now OPTIONS is a supported method lol
                allow = concat(of(OPTIONS), allow);
            } else {
                log(thr);
            }
            res = status.toBuilder().addHeader(ALLOW, allow.collect(joining(", "))).build();
        } catch (MediaTypeNotAcceptedException e) {
            log(thr);
            res = notAcceptable();
        } catch (MediaTypeUnsupportedException e) {
            log(thr);
            res = unsupportedMediaType();
        } catch (EndOfStreamException e) {
            ch.closeSafe();
            res = null;
        } catch (ResponseRejectedException e) {
            if (e.rejected().isInformational() &&
                e.reason() == PROTOCOL_NOT_SUPPORTED &&
                req.httpVersion().isLessThan(HTTP_1_1) &&
                ch.getServer().getConfig().ignoreRejectedInformational()) {
                // Ignore
                res = null;
            } else {
                log(thr);
                res = internalServerError();
            }
        } catch (ReadTimeoutException e) {
            res = requestTimeout();
        } catch (ResponseTimeoutException e) {
            log(thr);
            if (ch.isOpenForReading()) {
                logger().log(DEBUG, "Service unavailable, shutting down channel's input stream.");
            }
            ch.shutdownInputSafe();
            res = serviceUnavailable();
        } catch (MediaTypeParseException      |
                 IllegalResponseBodyException |
                 AmbiguousHandlerException e) {
            log(thr);
            res = internalServerError();
        } catch (Throwable everyThingElse) {
            log(thr);
            res = internalServerError();
        }
        
        if (res != null) {
            ch.writeFirst(res);
        }
    };
    
    private static void log(Throwable thr) {
        logger().log(ERROR, "Default error handler received:", thr);
    }
    
    private static System.Logger logger() {
        return System.getLogger(ErrorHandler.class.getPackageName());
    }
}