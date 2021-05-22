package alpha.nomagichttp;

import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.message.HttpVersionTooOldException;
import alpha.nomagichttp.message.MaxRequestHeadSizeExceededException;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.RequestBodyTimeoutException;
import alpha.nomagichttp.message.RequestHeadTimeoutException;
import alpha.nomagichttp.message.ResponseTimeoutException;
import alpha.nomagichttp.message.Responses;

import java.time.Duration;
import java.util.concurrent.Flow;

/**
 * Server configuration.<p>
 * 
 * The implementation is thread-safe.<p>
 * 
 * The implementation used if none is specified is {@link #DEFAULT}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com
 */
public interface Config
{
    /**
     * Values used:<p>
     * 
     * Max request head size = 8 000 <br>
     * Max error recovery attempts = 5 <br>
     * Thread pool-size = {@code Runtime.getRuntime().availableProcessors()}<br>
     * Reject clients using HTTP/1.0 = true
     */
    Config DEFAULT = new Config(){};
    
    /**
     * Returns the max number of bytes processed while parsing a request head
     * before giving up.<p>
     * 
     * Once the limit has been exceeded, a {@link
     * MaxRequestHeadSizeExceededException} is thrown.<p>
     * 
     * The configuration value is polled at the start of each new exchange.
     * 
     * @implSpec
     * The default implementation is equivalent to:
     * <pre>
     *     return 8_000;
     * </pre>
     * 
     * The default value corresponds to <a
     * href="https://tools.ietf.org/html/rfc7230#section-3.1.1">RFC 7230 §3.1.1</a>
     * as well as
     * <a href="https://stackoverflow.com/a/8623061/1268003">common practice</a>.
     * 
     * @return number of request head bytes processed before exception is thrown
     */
    default int maxRequestHeadSize() {
        return 8_000;
    }
    
    /**
     * Returns the max number of consecutive responses sent to a client of
     * classification 4XX (Client Error) and 5XX (Server Error) before closing
     * the client channel.<p>
     * 
     * Closing the channel after repeatedly unsuccessful exchanges increases
     * security.<p>
     * 
     * The configuration value is polled at the start of each new exchange.
     * 
     * @implSpec
     * The default implementation is equivalent to:
     * <pre>
     *     return 7;
     * </pre>
     * 
     * @return max number of consecutively unsuccessful responses
     *         before closing channel
     */
    default int maxUnsuccessfulResponses() {
        return 7;
    }
    
    /**
     * Returns the max number of attempts at recovering a failed request.<p>
     * 
     * The configuration has an effect only if the application has provide one
     * or more error handlers to the server.<p>
     * 
     * When all tries have been exhausted, the {@link ErrorHandler#DEFAULT
     * default error handler} will be called with the original exception.<p>
     * 
     * Successfully invoking an error handler (handler does not throw a
     * <i>different</i> exception instance) counts as one attempt.<p>
     * 
     * The recovery attempt count is saved and increment over the life span of
     * the HTTP exchange. It is not directly related to any given invocation of
     * a request handler.<p>
     * 
     * The configuration value will be polled at the start of each recovery
     * attempt.
     * 
     * @implSpec
     * The default implementation returns {@code 5}.
     * 
     * @return max number of attempts
     * 
     * @see ErrorHandler
     */
    default int maxErrorRecoveryAttempts() {
        return 5;
    }
    
    /**
     * Returns the number of request threads that are allocated for executing
     * HTTP exchanges.<p>
     * 
     * The value is retrieved at the time of the start of the first server
     * instance. For a later change of this value to have an effect, all server
     * instances must first stop.
     * 
     * @implSpec
     * The default implementation returns {@link Runtime#availableProcessors()}.
     * 
     * @return thread pool size
     */
    default int threadPoolSize() {
        return Runtime.getRuntime().availableProcessors();
    }
    
    /**
     * Reject HTTP/1.0 clients, yes or no.<p>
     * 
     * By default, this method returns {@code false} and the server will
     * therefore accept HTTP/1.0 clients.<p>
     * 
     * Rejection takes place through a server-thrown {@link
     * HttpVersionTooOldException} which by default gets translated to a
     * "426 Upgrade Required" response.<p>
     * 
     * HTTP/1.0 does not by default support persistent connections and there
     * are a number of issues related to HTTP/1.0's Keep-Alive mechanism which
     * the NoMagicHTTP server does not support. All HTTP/1.0 connections will
     * therefore close after each response, which is inefficient. It's
     * recommended to override this value with {@code true}. As a library
     * however, we have to be backwards compatible and support as many
     * applications as possible "out of the box", hence the {@code false}
     * default.<p>
     * 
     * The configuration value will be polled at the beginning of each HTTP
     * exchange.<p>
     * 
     * Note that HTTP/0.9 or older clients are always rejected (can not be
     * configured differently).
     * 
     * @implSpec
     * The default implementation returns {@code false}.
     * 
     * @return whether or not to reject HTTP/1.0 clients
     */
    default boolean rejectClientsUsingHTTP1_0() {
        return false;
    }
    
    /**
     * Ignore rejected 1XX (Informational) responses when they fail to be sent
     * to a HTTP/1.0 client.<p>
     * 
     * The default value is {@code true} and the application can safely write
     * 1XX (Informational) responses to the channel without concern for old
     * incompatible clients.<p>
     * 
     * If this option is disabled (changed to return false), then the default
     * error handler will instead of ignoring the failure, write a final 500
     * (Internal Server Error) response as an alternative to the failed
     * response, meaning that the application will then not be able to write its
     * intended final response. This also means that the application would have
     * to query the active HTTP version ({@link Request#httpVersion()}) and
     * restrain itself from attempting to send interim responses to HTTP/1.0
     * clients.<p>
     * 
     * The configuration value will be polled by the {@link
     * ErrorHandler#DEFAULT default error handler} for each handled relevant
     * case.
     * 
     * @implSpec
     * The default implementation returns {@code true}.
     * 
     * @return whether or not to ignore failed 1XX (Informational) responses
     *         sent to HTTP/1.0 clients
     */
    default boolean ignoreRejectedInformational() {
        return true;
    }
    
    /**
     * Immediately respond a 100 (Continue) interim response to a request with a
     * {@code Expect: 100-continue} header.<p>
     * 
     * By default, this value is {@code false} leaving the client and
     * application in control.<p>
     * 
     * Even when {@code false}, the server will respond a 100 (Continue)
     * response to the client on first access of a non-empty request body (all
     * methods in {@link Request.Body} except {@link Request.Body#isEmpty()})
     * unless one has already been sent. This is convenient for the application
     * developer who does not need to know anything about this particular
     * protocol feature.<p>
     * 
     * Independent of the configured value, the server never attempts to
     * automatically send a 100 (Continue) response to a HTTP/1.0 client since
     * HTTP/1.0 does not support interim responses (
     * <a href="https://tools.ietf.org/html/rfc7231#section-5.1.1">RFC 7231 §5.1.1</a>).<p>
     * 
     * The configuration value is polled once on each new request.
     * 
     * @implSpec
     * The default implementation returns {@code false}.
     * 
     * @return whether or not to immediately respond a 100 (Continue)
     *         interim response to a request with a {@code Expect: 100-continue} header
     * 
     * @see HttpConstants.StatusCode#ONE_HUNDRED
     */
    default boolean immediatelyContinueExpect100() {
        return false;
    }
    
    /**
     * Returns the max duration allowed for idle connections, after which, the
     * connection will begin closing.<p>
     * 
     * The "idling" is detected by different timers timing out on inactivity
     * during different phases of the HTTP exchange; parsing a request head,
     * processing a request body, waiting on a response from the application and
     * finally writing a response to the underlying channel. The timeout
     * duration for each timer is the value returned from this method and is
     * polled at various points throughout the HTTP exchange.<p>
     * 
     * <strong>Request Timeout</strong><p>
     * 
     * A timeout while a request head is expected or in-flight will cause the
     * server to throw a {@link RequestHeadTimeoutException}, translated by the
     * {@link ErrorHandler#DEFAULT default error handler} to a 408 (Request
     * Timeout).<p>
     * 
     * If a request body is expected, then another timer will start as soon as
     * the request head parser terminates and ends when the request body
     * subscription terminates. When the timer elapses, a {@link
     * RequestBodyTimeoutException} is either thrown by the server or - if there
     * is a body subscriber - delivered to the subscriber's {@link
     * Flow.Subscriber#onError(Throwable) onError()} method. If it was thrown,
     * or the subscriber is the server's own discarding body subscriber, then
     * the error will pass through the error handler(s), the default of which
     * translates the exception to a 408 (Request Timeout).<p>
     * 
     * An application-installed body subscriber must deal with the timeout
     * exception (for example by responding {@link Responses#requestTimeout()}),
     * just as the application needs to be prepared to deal with any other error
     * passed to the body subscriber. Failure to deal with the exception, will
     * likely and eventually result in a {@link ResponseTimeoutException}
     * instead, as discussed in the next section.<p>
     * 
     * The request timers are not reset after each byte received on the wire.
     * The timers are only reset on each bytebuffer observed through the
     * processing chain. This means that an extremely slow client may cause a
     * request timeout even though the connection is technically still making
     * progress. Similarly, a request timeout may also happen because the
     * application's body subscriber takes too long processing a bytebuffer. It
     * can be argued that the purpose of the timeouts are not so much to protect
     * against stale connections as they are to protect against HTTP exchanges
     * not making progress, whoever is at fault.<p>
     * 
     * This coarse-grained resolution for timer reset is by design, as it
     * improves greatly the server performance and also works as an automatic
     * protection against slow clients hogging server connections.<p>
     * 
     * The in-practice minimum acceptable transfer rate can be calculated. The
     * default timeout duration is one and a half minute and the server's buffer
     * size is 16 384 bytes. This works out to a <i>possible</i> (dependent on
     * request size, and so on) timeout for clients sending data on average
     * equal to or slower than 1.456 kb/s (0.001456 Mbit/s) for one and a half
     * minute. This calculated minimum rate is well within the transfer rate of
     * cellular networks even older than 2G (10-15% of Cellular Digital Packet
     * Data rate). But if the application's clients are expected to be on Mars,
     * then perhaps the timeout ought to be increased.<p>
     * 
     * <strong>Response Timeout</strong><p>
     * 
     * Like the request timeout, different response timeouts are active at
     * various stages of the HTTP exchange. Unlike the request timeout, there's
     * only one exception type to be aware about: {@link
     * ResponseTimeoutException}. The outcome of the exception is dependent on
     * where the exception occurred.<p>
     * 
     * One response timer will timeout on failure of the application to deliver
     * a response to the {@link ClientChannel}. This timer starts when the
     * request timer(s) end or times out, and so the ClientChannel will never
     * timeout while a request is still actively being processed. The timer is
     * reset for each response given (after possible stage completion). A
     * response producer that needs more time can reset the timer by sending a
     * 1XX (Informational) interim response.<p>
     * 
     * Assuming that the write stream is still open when the exception occurs,
     * the ClientChannel's {@code ResponseTimeoutException} is delivered to the
     * error handler(s), the default of which translates it to a 503 (Service
     * Unavailable).<p>
     * 
     * A second response timer is active only when the server's response body
     * subscriber has outstanding demand and the timer is reset on each item
     * received. I.e. the application must not only ensure that response objects
     * are given to the ClientChannel in a timely manner but also that message
     * body bytes are emitted in a timely manner.<p>
     * 
     * Unlike the first timer discussed, a response body timeout will never be
     * delivered to the error handler(s). The exception will immediately
     * forcefully close the channel.<p>
     * 
     * Analogous to the built-in protection against slow clients when receiving
     * data, a third response timer will cause the underlying channel write
     * operation to abort for response body bytebuffers not fully sent before
     * the duration elapses. The exception too will not be delivered to the
     * error handler(s). The application can still chose to publish very large
     * response body bytebuffers without worrying about a possible timeout due
     * to the increased time it may take to send a large buffer. The server will
     * internally slice the buffer if need be.<p>
     * 
     * Any timeout exception not delivered to the exception handler(s) is
     * logged.<p>
     * 
     * Word of caution: There is currently no mechanism put in place to unblock
     * a stuck thread. For example, if the server's thread who subscribes to the
     * response body is immediately and indefinitely blocked, then no error
     * handler will ever be called and the timeout exception will never be
     * logged. The connection will close, but that's it. Never block a thread.
     * 
     * @implSpec
     * The default implementation returns {@code Duration.ofSeconds(90)}.
     * 
     * @return a max allowed duration for idle connections
     */
    default Duration timeoutIdleConnection() {
        return Duration.ofSeconds(90);
    }
}