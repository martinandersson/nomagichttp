package alpha.nomagichttp.handler;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.NonThrowingChain;
import alpha.nomagichttp.action.AfterAction;
import alpha.nomagichttp.action.BeforeAction;
import alpha.nomagichttp.message.AbstractSizeException;
import alpha.nomagichttp.message.IllegalResponseBodyException;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.RequestLineParseException;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;
import alpha.nomagichttp.route.MethodNotAllowedException;
import alpha.nomagichttp.route.NoHandlerResolvedException;
import alpha.nomagichttp.route.NoRouteFoundException;

import java.util.stream.Stream;

import static alpha.nomagichttp.HttpConstants.HeaderName.ALLOW;
import static alpha.nomagichttp.HttpConstants.Method.OPTIONS;
import static alpha.nomagichttp.HttpConstants.StatusCode.isClientError;
import static alpha.nomagichttp.HttpConstants.StatusCode.isRedirection;
import static alpha.nomagichttp.HttpConstants.StatusCode.isServerError;
import static alpha.nomagichttp.message.Responses.internalServerError;
import static alpha.nomagichttp.message.Responses.noContent;
import static alpha.nomagichttp.message.Responses.teapot;
import static alpha.nomagichttp.util.ScopedValues.httpServer;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Stream.concat;
import static java.util.stream.Stream.of;

/**
 * Optionally translates an {@code Exception} into a response.<p>
 * 
 * The server calls error handlers to handle uncaught exceptions that are thrown
 * from the request thread during the HTTP exchange (which begins as soon as a
 * single byte has been received).<p>
 * 
 * The handlers are not called indiscriminately for all exceptions at all times.
 * For example, {@link InterruptedException} is never passed to the handlers (in
 * this case, the client channel is closed, and that is effectively the end of
 * both the exchange and the request thread).<p>
 * 
 * The purpose of the error handler is to cater the client with a response even
 * in the event of a failure. Therefore, an error handler should return a
 * response, either by returning one directly, or yielding to the next error
 * handler in the processing chain, which will eventually be the server's own
 * {@link #BASE} handler.<p>
 * 
 * {@snippet :
 *   ErrorHandler forMyExpected = (exception, chain, request) -> {
 *       if (exception instanceof MyExpectedException familiar) {
 *           return someResponse(familiar);
 *       }
 *       // Don't know what this is, so try the next error handler
 *       return chain.proceed();
 *   };
 * }
 * 
 * An application-installed handler can be used for many different purposes, not
 * only to handle specific types. One example would be to decorate the base
 * handler and modify or replace its response.<p>
 * 
 * Error handlers will be called in the same order they were registered with the
 * server.<p>
 * 
 * The server will call error handlers only if the channel remains open for
 * writing at the time of the error, and only if no response bytes have already
 * been written.<p>
 * 
 * The error handler should not need to store exchange-dependent state. A retry
 * mechanism is best implemented as a {@link BeforeAction} or in the
 * {@link RequestHandler} itself.<p>
 * 
 * The error handler must never throw an exception.<p>
 * 
 * The error handler must be thread-safe, as it may be called concurrently.<p>
 * 
 * The error handler implementation does not need to implement {@code hashCode}
 * and {@code equals}.<p>
 * 
 * Just to be perfectly clear; error handlers are optional. Any executed entity
 * is free to handle exceptions however the application sees fit.
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
     * {@link AfterAction}s (as they were installed together with a pattern from
     * which to extrapolate the keys). All path parameter accessors of the
     * request object given to this method will throw an
     * {@link UnsupportedOperationException}.
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
     * Is the last handler in the exception-processing chain.<p>
     * 
     * Firstly, this method implements some features provided through
     * configuration. For the moment, that is only
     * {@link Config#implementMissingOptions()}.<p>
     * 
     * Secondly, if the exception implements {@link WithResponse}, the handler
     * calls {@link WithResponse#getResponse()} and returns the provided
     * response.<p>
     * 
     * Lastly, {@link Responses#internalServerError()} is returned.
     */
    // TODO: [Any?]TimeoutException; ask client to "retry later"?
    ErrorHandler BASE = (exc, chainIsNull, req) -> {
        if (exc instanceof MethodNotAllowedException e) {
            Response status = e.getResponse();
            assert isErrorStatusCode(status.statusCode());
            Stream<String> allow = e.getRoute().supportedMethods();
            if (req.method().equals(OPTIONS) && httpServer().getConfig().implementMissingOptions()) {
                status = noContent();
                // Now OPTIONS is a supported method lol
                allow = concat(of(OPTIONS), allow);
            } else {
                log(exc);
            }
            return status.toBuilder()
                         .setHeader(ALLOW, allow.collect(joining(", ")))
                         .build();
        }
        tryLog(exc);
        if (exc instanceof WithResponse trait) {
            var rsp = trait.getResponse();
            int code = rsp.statusCode();
            if (!isErrorStatusCode(code)) {
                logger().log(WARNING, () -> """
                    For being an advisory fallback response, \
                    the status code %s makes no sense.""".formatted(code));
                rsp = teapot();
            }
            // log internal server error?
            // (ResponseRejectedException)
            return rsp;
        }
        // Expected
        //   MediaTypeParseException
        //   IllegalLockUpgradeException
        //   FileLockTimeoutException
        log(exc);
        return internalServerError();
    };
    
    private static boolean isErrorStatusCode(int code) {
        return isRedirection(code) || isClientError(code) || isServerError(code);
    }
    
    private static void tryLog(Exception exc) {
        try {
            throw exc;
        } catch (AbstractSizeException |
                 NoRouteFoundException |
                 NoHandlerResolvedException |
                 IllegalResponseBodyException doIt) {
            log(exc);
        } catch (Exception maybeLaterInTheProcess) {
            // Empty
        }
    }
    
    private static void log(Exception exc) {
        logger().log(ERROR, "This might be interesting", exc);
    }
    
    private static System.Logger logger() {
        return System.getLogger(ErrorHandler.class.getPackageName());
    }
}