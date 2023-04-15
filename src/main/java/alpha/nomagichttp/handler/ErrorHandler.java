package alpha.nomagichttp.handler;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.NonThrowingChain;
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
import alpha.nomagichttp.message.MaxRequestBodyBufferSizeException;
import alpha.nomagichttp.message.MaxRequestHeadSizeException;
import alpha.nomagichttp.message.MaxRequestTrailersSizeException;
import alpha.nomagichttp.message.MediaTypeParseException;
import alpha.nomagichttp.message.ReadTimeoutException;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.RequestLineParseException;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.ResponseTimeoutException;
import alpha.nomagichttp.message.Responses;
import alpha.nomagichttp.message.UnsupportedTransferCodingException;
import alpha.nomagichttp.route.AmbiguousHandlerException;
import alpha.nomagichttp.route.MediaTypeNotAcceptedException;
import alpha.nomagichttp.route.MediaTypeUnsupportedException;
import alpha.nomagichttp.route.MethodNotAllowedException;
import alpha.nomagichttp.route.NoRouteFoundException;

import java.util.stream.Stream;

import static alpha.nomagichttp.HttpConstants.HeaderName.ALLOW;
import static alpha.nomagichttp.HttpConstants.Method.OPTIONS;
import static alpha.nomagichttp.handler.ResponseRejectedException.Reason;
import static alpha.nomagichttp.message.Responses.badRequest;
import static alpha.nomagichttp.message.Responses.entityTooLarge;
import static alpha.nomagichttp.message.Responses.httpVersionNotSupported;
import static alpha.nomagichttp.message.Responses.internalServerError;
import static alpha.nomagichttp.message.Responses.methodNotAllowed;
import static alpha.nomagichttp.message.Responses.noContent;
import static alpha.nomagichttp.message.Responses.notAcceptable;
import static alpha.nomagichttp.message.Responses.notFound;
import static alpha.nomagichttp.message.Responses.notImplemented;
import static alpha.nomagichttp.message.Responses.requestTimeout;
import static alpha.nomagichttp.message.Responses.serviceUnavailable;
import static alpha.nomagichttp.message.Responses.unsupportedMediaType;
import static alpha.nomagichttp.message.Responses.upgradeRequired;
import static alpha.nomagichttp.util.ScopedValues.channel;
import static alpha.nomagichttp.util.ScopedValues.httpServer;
import static java.lang.System.Logger.Level;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Stream.concat;
import static java.util.stream.Stream.of;

/**
 * Translates an {@code Exception} into a response.<p>
 * 
 * Error handlers may be used to handle generic and server-global errors.
 * Another use case is to short-circuit the server's {@link #BASE base handler}
 * in favor of creating a customized response.<p>
 * 
 * The error handlers will be called to handle exceptions that occur during the
 * HTTP exchange. For example maybe request parsing failed or the request
 * processing chain failed.<p>
 * 
 * The purpose of error handlers is to cater the client with a response even in
 * the event of a failure. Therefore, an error handler must return a response,
 * either by returning one directly, or yielding to the next error handler,
 * which will eventually be the server's base handler, which has a fallback
 * response for all exceptions.
 * 
 * <pre>{@code
 *   ErrorHandler forMyExpected = (exception, chain, request) -> {
 *       if (exception instanceof MyExpectedException e) {
 *           return someResponse();
 *       }
 *       // Don't know what this is, so try the next error handler
 *       return chain.proceed();
 *   };
 * }</pre>
 * 
 * Error handlers will be called in the same order they were registered with the
 * server.<p>
 * 
 * The server will call error handlers only if the channel remains open for
 * writing at the time of the error, and only if no response bytes have already
 * been written. The error handler itself must never throw an exception.<p>
 * 
 * An exception that can not- or couldn't be handled will be logged on level
 * {@link Level#WARNING WARNING} or {@link Level#ERROR ERROR}.<p>
 * 
 * The error handler must be thread-safe, as it may be called concurrently.<p>
 * 
 * The error handler should not need to store exchange-dependent state. A retry
 * mechanism is best implemented as a {@link BeforeAction} or in the
 * {@link RequestHandler} itself.<p>
 * 
 * The error handler implementation does not need to implement {@code hashCode}
 * and {@code equals}.<p>
 * 
 * Just to be perfectly clear; error handlers are optional. Any executed entity
 * is free to handle exceptions however the application sees fit. For example, a
 * request handler could catch an exception and return a fallback response from
 * the catch block.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see ErrorHandler#apply(Exception, NonThrowingChain, Request)
 */
// TODO: 1) Rename to ExceptionHandler
//       2) We want to have a fallback response for outbound FileNotFoundExc
//       3) CharacterCodingException = Bad Request, and document
@FunctionalInterface
public interface ErrorHandler
{
    /**
     * Produces a response.<p>
     * 
     * The given exception is what this error handler ought to process into a
     * response. Either directly or by yielding control to the rest of the
     * chain.<p>
     * 
     * The {@code Request} object may be null depending on how much progress was
     * made in the HTTP exchange before the error occurred. For example, if the
     * error is a {@link RequestLineParseException}, then the request object
     * will obviously not be available.<p>
     * 
     * The request object's path parameters are only available to
     * {@link BeforeAction}s, the {@link RequestHandler}, and
     * {@link AfterAction}s (as they provided a pattern from which to
     * extrapolate the keys). All path parameter accessors of the request object
     * given to this method will throw an {@link UnsupportedOperationException}.
     * 
     * @param exc the error (never null)
     * @param chain yielder of control (never null)
     * @param req request object
     * 
     * @return a fallback response
     *         (must be {@linkplain Response#isFinal() final})
     * 
     * @see ErrorHandler
     */
    Response apply(Exception exc, NonThrowingChain chain, Request req);
    
    /**
     * Is the base error handler used by the server if no other
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
     *     <th scope="row"> {@link UnsupportedTransferCodingException} </th>
     *     <td> None </td>
     *     <td> No </td>
     *     <td> {@link Responses#notImplemented()}} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link MaxRequestHeadSizeException} </th>
     *     <td> None </td>
     *     <td> Yes </td>
     *     <td> {@link Responses#entityTooLarge()} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link MaxRequestTrailersSizeException} </th>
     *     <td> None </td>
     *     <td> Yes </td>
     *     <td> {@link Responses#entityTooLarge()} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row"> {@link MaxRequestBodyBufferSizeException} </th>
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
     *     <th scope="row"> {@link EndOfStreamException} </th>
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
     *     <td> Reason is
     *          {@link Reason#CLIENT_PROTOCOL_UNKNOWN_BUT_NEEDED
     *                   CLIENT_PROTOCOL_UNKNOWN_BUT_NEEDED}</td>
     *     <td> Yes </td>
     *     <td> {@link Responses#internalServerError()} </td>
     *   </tr>
     *   <tr>
     *     <th scope="row">{@link ResponseRejectedException} </th>
     *     <td> Reason is
     *          {@link Reason#CLIENT_PROTOCOL_DOES_NOT_SUPPORT
     *                   CLIENT_PROTOCOL_DOES_NOT_SUPPORT}</td>
     *     <td> No </td>
     *     <td> {@link Responses#upgradeRequired(String)} </td>
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
     *     <th scope="row"> <i>{@code Everything else}</i> </th>
     *     <td> None </td>
     *     <td> Yes </td>
     *     <td> {@link Responses#internalServerError()} </td>
     *   </tr>
     *   </tbody>
     * </table>
     */
    // TODO: TimeoutException; ask client to "retry later"
    ErrorHandler BASE = (exc, chainIsNull, req) -> {
        final Response res;
        try {
            throw exc;
        } catch (RequestLineParseException   |
                 HeaderParseException        |
                 HttpVersionParseException   |
                 BadHeaderException          |
                 BadRequestException         |
                 IllegalRequestBodyException |
                 DecoderException            |
                 EndOfStreamException e) {
            res = badRequest();
        } catch (HttpVersionTooOldException e) {
            res = upgradeRequired(e.getUpgrade());
        } catch (HttpVersionTooNewException e) {
            res = httpVersionNotSupported();
        } catch (UnsupportedTransferCodingException e) {
            res = notImplemented();
        } catch (MaxRequestHeadSizeException |
                 MaxRequestTrailersSizeException |
                 MaxRequestBodyBufferSizeException e) {
            log(exc);
            res = entityTooLarge();
        } catch (NoRouteFoundException e) {
            log(exc);
            res = notFound();
        } catch (MethodNotAllowedException e) {
            Response status = methodNotAllowed();
            Stream<String> allow = e.getRoute().supportedMethods();
            if (req.method().equals(OPTIONS) && httpServer().getConfig().implementMissingOptions()) {
                status = noContent();
                // Now OPTIONS is a supported method lol
                allow = concat(of(OPTIONS), allow);
            } else {
                log(exc);
            }
            res = status.toBuilder().addHeader(ALLOW, allow.collect(joining(", "))).build();
        } catch (MediaTypeNotAcceptedException e) {
            log(exc);
            res = notAcceptable();
        } catch (MediaTypeUnsupportedException e) {
            log(exc);
            res = unsupportedMediaType();
        } catch (ResponseRejectedException e) {
            res = switch (e.reason()) {
                case CLIENT_PROTOCOL_UNKNOWN_BUT_NEEDED -> {
                    log(exc);
                    yield internalServerError();
                }
                case CLIENT_PROTOCOL_DOES_NOT_SUPPORT -> upgradeRequired("HTTP/1.1");
                default -> throw new AssertionError();
            };
        } catch (ReadTimeoutException e) {
            res = requestTimeout();
        } catch (ResponseTimeoutException e) {
            log(exc);
            if (channel().isInputOpen()) {
                logger().log(DEBUG,
                    "Service unavailable, shutting down channel's input stream.");
                channel().shutdownInput();
            }
            res = serviceUnavailable();
        } catch (Exception everythingElse) {
            // Expected
            //   MediaTypeParseException
            //   AmbiguousHandlerException
            //   IllegalResponseBodyException
            //   IllegalLockUpgradeException
            log(exc);
            res = internalServerError();
        }
        return res;
    };
    
    private static void log(Exception exc) {
        logger().log(ERROR, "This might be interesting", exc);
    }
    
    private static System.Logger logger() {
        return System.getLogger(ErrorHandler.class.getPackageName());
    }
}