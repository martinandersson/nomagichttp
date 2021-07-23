package alpha.nomagichttp;

import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.ResponseRejectedException;
import alpha.nomagichttp.message.HttpVersionTooOldException;
import alpha.nomagichttp.message.MaxRequestHeadSizeExceededException;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.RequestBodyTimeoutException;
import alpha.nomagichttp.message.RequestHeadTimeoutException;
import alpha.nomagichttp.message.ResponseTimeoutException;
import alpha.nomagichttp.message.Responses;
import alpha.nomagichttp.route.MethodNotAllowedException;

import java.time.Duration;
import java.util.concurrent.Flow;

/**
 * Server configuration.<p>
 * 
 * The implementation is immutable and thread-safe.<p>
 * 
 * The implementation used if none is specified is {@link #DEFAULT}.<p>
 * 
 * Any configuration object can be turned into a builder for customization. The
 * static method {@link #configuration()} is a shortcut for {@code
 * Config.DEFAULT.toBuilder()}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com
 */
public interface Config
{
    /**
     * Values used:<p>
     * 
     * Max request head size = 8 000 <br>
     * Max unsuccessful responses = 7 <br>
     * Max error recovery attempts = 5 <br>
     * Thread pool-size = {@code Runtime.getRuntime().availableProcessors()}<br>
     * Reject clients using HTTP/1.0 = true <br>
     * Ignore rejected informational = true <br>
     * Immediately continue Expect 100 = false <br>
     * Timeout idle connection = 90 seconds
     */
    Config DEFAULT = DefaultConfig.DefaultBuilder.ROOT.build();
    
    /**
     * Returns the max number of bytes processed while parsing a request head
     * before giving up.<p>
     * 
     * Once the limit has been exceeded, a {@link
     * MaxRequestHeadSizeExceededException} is thrown.<p>
     * 
     * The default implementation returns {@code 8_000}.
     * 
     * The default value corresponds to <a
     * href="https://tools.ietf.org/html/rfc7230#section-3.1.1">RFC 7230 §3.1.1</a>
     * as well as
     * <a href="https://stackoverflow.com/a/8623061/1268003">common practice</a>.
     * 
     * @return number of request head bytes processed before exception is thrown
     */
    int maxRequestHeadSize();
    
    /**
     * Returns the max number of consecutive responses sent to a client of
     * classification 4XX (Client Error) and 5XX (Server Error) before closing
     * the client channel.<p>
     * 
     * Closing the channel after repeatedly unsuccessful exchanges increases
     * security.<p>
     * 
     * The default implementation returns {@code 7}.
     * 
     * @return max number of consecutively unsuccessful responses
     *         before closing channel
     */
    int maxUnsuccessfulResponses();
    
    /**
     * Returns the max number of attempts at recovering a failed HTTP
     * exchange.<p>
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
     * The default implementation returns {@code 5}.
     * 
     * @return max number of attempts
     * 
     * @see ErrorHandler
     */
    int maxErrorRecoveryAttempts();
    
    /**
     * Returns the number of request threads that are allocated for processing
     * HTTP exchanges.<p>
     * 
     * The value is retrieved only once at the time of the start of the first
     * server instance. All subsequent servers will share the same thread pool.
     * To effectively change the thread pool size, all server instances must
     * first stop.<p>
     * 
     * The default implementation returns {@code 3} or {@link
     * Runtime#availableProcessors()}, whichever one is the greatest.<p>
     * 
     * The request thread is only supposed to perform short CPU-bound work - not
     * idling/being dormant awaiting I/O. Hence, the desired target is equal to
     * the number of CPUs available. It is not very conceivable that the
     * throughput will increase if the ceiling is raised - in particular since
     * the HTTP server is natively asynchronous and therefore already possess
     * the ability to switch which requests and responses are being processed
     * based on what data is readily available for consumption - although to
     * date no experiments on raising the ceiling have been made.<p>
     * 
     * The default implementation imposes a lower floor set to 3 as a minimum
     * pool size.<p>
     * 
     * A modern computer can be expected to have many cores, in particular
     * server machines (32+ cores). But all cores are not necessarily available
     * to the JVM. A server machine in production often run a plethora of
     * containers (including multiple instances of the same app), each of which
     * is assigned a reserved or limited set of the machine's resources, which
     * often translates to a very small number of CPU:s for any one particular
     * JVM. It is not uncommon for a poorly configured environment to expose
     * only 1 single CPUif not less. Without a floor on the pool size, this
     * would be not so great for the throughput and that is why the default
     * implementation has a minimum size of 3, which aims to increase the level
     * of concurrency albeit at a small cost of OS context switching. 
     * 
     * @return thread pool size
     * @see HttpServer
     */
    int threadPoolSize();
    
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
     * Note that HTTP/0.9 or older clients are always rejected (can not be
     * configured differently).<p>
     * 
     * The default implementation returns {@code false}.
     * 
     * @return whether or not to reject HTTP/1.0 clients
     */
    boolean rejectClientsUsingHTTP1_0();
    
    /**
     * Ignore rejected 1XX (Informational) responses when they fail to be sent
     * to a HTTP/1.0 client.<p>
     * 
     * The default value is {@code true} and the application can safely write
     * 1XX (Informational) responses to the channel without concern for old
     * incompatible clients.<p>
     * 
     * Caution: If this option is disabled (changed to return false), then the
     * default error handler will instead of ignoring the failure, write a final
     * 500 (Internal Server Error) response, meaning that the application will
     * not be able to write its intended final response, or may even write its
     * final response to a another subsequent HTTP exchange. Turning this option
     * off necessitates that the application must query the active HTTP version
     * ({@link Request#httpVersion()}) and restrain itself from attempting to
     * send interim responses to HTTP/1.0 clients. Alternatively, install a
     * custom error handler for {@link ResponseRejectedException}.<p>
     * 
     * The default implementation returns {@code true}.
     * 
     * @return whether or not to ignore failed 1XX (Informational) responses
     *         sent to HTTP/1.0 clients
     */
    boolean ignoreRejectedInformational();
    
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
     * The default implementation returns {@code false}.
     * 
     * @return whether or not to immediately respond a 100 (Continue)
     *         interim response to a request with a {@code Expect: 100-continue} header
     * 
     * @see HttpConstants.StatusCode#ONE_HUNDRED
     */
    boolean immediatelyContinueExpect100();
    
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
     * only one exception type to be aware of: {@link ResponseTimeoutException}.
     * The outcome of the exception is dependent on where the exception
     * occurred.<p>
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
     * and forcefully close the channel.<p>
     * 
     * Analogous to the built-in protection against slow clients when receiving
     * data, a third response timer will cause the underlying channel write
     * operation to abort for response body bytebuffers not fully sent before
     * the duration elapses. This exception will also not be delivered to the
     * error handler(s). The application can chose to publish very large
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
     * logged. The connection will close, but that's it. Never block a thread
     * from the server.<p>
     * 
     * The default implementation returns {@code Duration.ofSeconds(90)}.
     * 
     * @return a max allowed duration for idle connections
     */
    Duration timeoutIdleConnection();
    
    /**
     * If {@code true} (which is the default), the {@link ErrorHandler#DEFAULT
     * default error handler} will respond 204 (No Content) with the {@value
     * HttpConstants.HeaderKey#ALLOW} header populated to a request handler
     * resolution that ends with a {@link MethodNotAllowedException} if the
     * requested HTTP method is {@value HttpConstants.Method#OPTIONS}.<p>
     * 
     * Even if the default value for this configuration is {@code true}, the
     * application's route can still freely implement the {@code OPTIONS} method
     * or the application can configure an error handler that handles the {@code
     * MethodNotAllowedException} however it sees fit.<p>
     * 
     * If this methods returns {@code false}, then the default error handler
     * will simply respond a 405 (Method Not Allowed) response as it normally
     * do for all {@code MethodNotAllowedException}s.<p>
     * 
     * In human speech; if the application does not implement the {@code
     * OPTIONS} method for a given route, and this configuration value returns
     * false, the error would have been treated as a <i>client error</i>. But by
     * default, even if the application does not implement the {@code OPTIONS}
     * method, a <i>successful</i> response will be returned. Disabling this
     * configuration disables the {@code OPTIONS} method completely unless the
     * application explicitly add a request handler that supports the method.
     * 
     * @return see JavaDoc
     */
    boolean implementMissingOptions();
    
     /**
     * Returns the builder instance that built this configuration.<p>
     * 
     * The builder may be used to modify configuration values.
     * 
     * @return the builder instance that built this configuration object
     */
    Config.Builder toBuilder();
    
    /**
     * Returns the builder used to build the default configuration.
     * 
     * @return the builder used to build the default configuration
     * @see #toBuilder()
     */
    static Config.Builder configuration() {
        return DEFAULT.toBuilder();
    }
    
    /**
     * Builder of a {@link Config}.<p>
     * 
     * The builder can be used as a template to modify configuration state. Each
     * method returns a new builder instance representing the new state. The API
     * should be used in a fluent style. There's generally no reason to save a
     * builder reference as the builder that built a configuration object can be
     * retrieved using {@link Config#toBuilder()}<p>
     * 
     * The implementation is thread-safe.<p>
     * 
     * The implementation does not necessarily implement {@code hashCode()} and
     * {@code equals()}.
     * 
     * @author Martin Andersson (webmaster at martinandersson.com)
     */
    interface Builder {
        /**
         * Set a new value.
         * 
         * The value can be any integer, although a value too small (or
         * negative) will risk rejecting all requests.
         * 
         * @param newVal new value
         * @return a new builder representing the new state
         * @see Config#maxRequestHeadSize()
         */
        Builder maxRequestHeadSize(int newVal);
        
        /**
         * Set a new value.
         * 
         * The value can be any integer, although a value too small (or
         * negative) will risk closing the connection early.
         * 
         * @param newVal new value
         * @return a new builder representing the new state
         * @see Config#maxUnsuccessfulResponses()
         */
        Builder maxUnsuccessfulResponses(int newVal);
        
        /**
         * Set a new value.
         * 
         * The value can be any integer, although a value too small (or
         * negative) will risk not invoking error handlers provided by the
         * application.
         * 
         * @param newVal new value
         * @return a new builder representing the new state
         * @see Config#maxErrorRecoveryAttempts()
         */
        Builder maxErrorRecoveryAttempts(int newVal);
        
        /**
         * Set a new value.
         * 
         * The value can be any integer, although a zero value (or negative)
         * will likely cause the HttpServer {@code start()} to blow up with an
         * {@code IllegalArgumentException}.
         * 
         * @param newVal new value
         * @return a new builder representing the new state
         * @see Config#threadPoolSize()
         */
        Builder threadPoolSize(int newVal);
        
        /**
         * Set a new value.
         * 
         * @param newVal new value
         * @return a new builder representing the new state
         * @see Config#rejectClientsUsingHTTP1_0()
         */
        Builder rejectClientsUsingHTTP1_0(boolean newVal);
        
        /**
         * Set a new value.
         * 
         * @param newVal new value
         * @return a new builder representing the new state
         * @see Config#ignoreRejectedInformational()
         */
        Builder ignoreRejectedInformational(boolean newVal);
        
        /**
         * Set a new value.
         * 
         * @param newVal new value
         * @return a new builder representing the new state
         * @see Config#immediatelyContinueExpect100()
         */
        Builder immediatelyContinueExpect100(boolean newVal);
        
        /**
         * Set a new value.
         * 
         * The value can be any duration, although a too short (or negative)
         * duration will effectively make the server useless.
         * 
         * @param newVal new value
         * @return a new builder representing the new state
         * @throws NullPointerException if {@code newVal} is {@code null}
         * @see Config#timeoutIdleConnection()
         */
        Builder timeoutIdleConnection(Duration newVal);
        
        /**
         * Set a new value.
         * 
         * @param newVal new value
         * @return a new builder representing the new state
         * @see Config#implementMissingOptions()
         */
        Builder implementMissingOptions(boolean newVal);
        
        /**
         * Builds a configuration object.
         * 
         * @return the configuration object
         */
        Config build();
    }
}