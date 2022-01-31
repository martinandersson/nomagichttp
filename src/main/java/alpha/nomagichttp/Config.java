package alpha.nomagichttp;

import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.ResponseRejectedException;
import alpha.nomagichttp.message.HttpVersionTooOldException;
import alpha.nomagichttp.message.MaxRequestHeadSizeExceededException;
import alpha.nomagichttp.message.MaxRequestTrailersSizeExceededException;
import alpha.nomagichttp.message.ReadTimeoutException;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.ResponseTimeoutException;
import alpha.nomagichttp.message.Responses;
import alpha.nomagichttp.route.MethodNotAllowedException;
import alpha.nomagichttp.route.Route;

import java.time.Duration;

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
     * @return number of request head bytes processed before exception
     */
    int maxRequestHeadSize();
    
    /**
     * Returns the max number of bytes processed while parsing request trailers
     * before giving up.<p>
     * 
     * Once the limit has been exceeded, a {@link
     * MaxRequestTrailersSizeExceededException} is thrown.<p>
     * 
     * The default implementation returns {@code 8_000}.
     * 
     * @return number of request trailer bytes processed before exception
     */
    int maxRequestTrailersSize();
    
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
     * The active count is bumped for each new error that the server attempts to
     * resolve through the {@link ErrorHandler}. When all tries have been
     * exhausted, the server will log the error and close the client channel, at
     * which point any ongoing read or write operation will fail.<p>
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
     * @return whether to reject HTTP/1.0 clients
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
     * @return whether to ignore failed 1XX (Informational) responses sent to
     *         HTTP/1.0 clients
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
     * @return whether to immediately respond a 100 (Continue) interim response
     *         to a request with a {@code Expect: 100-continue} header
     * 
     * @see HttpConstants.StatusCode#ONE_HUNDRED
     */
    boolean immediatelyContinueExpect100();
    
    /**
     * Max duration allowed for a channel read operation to complete.<p>
     * 
     * On timeout, the underlying read-stream will shut down and then a {@link
     * ReadTimeoutException} is thrown.<p>
     * 
     * If the timeout occurs while a request head is expected or in-flight, then
     * it'll be delivered to the error handler(s), the {@linkplain
     * ErrorHandler#DEFAULT default} of which translates the exception to a
     * {@linkplain Responses#requestTimeout() 408 (Request Timeout)}.<p>
     * 
     * If the timeout occurs while there is an active request body subscriber,
     * it'll be delivered to the subscriber's {@code onError} method. If the
     * subscriber is the server's own discarding body subscriber, then the error
     * will be delivered to the error handler(s). An application-installed body
     * subscriber must deal with the timeout exception just as it needs to be
     * prepared to deal with any other error passed to the subscriber. Failure
     * to handle the exception will eventually result in a {@code
     * ResponseTimeoutException} instead.<p>
     * 
     * The timer is only reset once a read-buffer has filled up and a new read
     * operation is initiated. Therefore, an extremely slow client may cause a
     * read timeout even though the connection is technically still making
     * progress. Similarly, the timeout may also happen because the
     * application's body subscriber takes too long processing a bytebuffer. It
     * can be argued that the purpose of the timeout is not so much to protect
     * against a stale connection but rather to protect against HTTP exchanges
     * not making progress, whoever is at fault.<p>
     * 
     * The minimum acceptable transfer rate can be calculated. The default
     * timeout duration is one and a half minute and the default server's buffer
     * size is 16 384 bytes. This works out to a possible (dependent on request
     * size, and so on) timeout for clients sending data on average equal to or
     * slower than 1.456 kb/s (0.001456 Mbit/s) for one and a half minute. This
     * calculated minimum rate is well within the transfer rate of cellular
     * networks even older than 2G (10-15% of Cellular Digital Packet Data
     * rate). But if the application's clients are expected to be on Mars, then
     * perhaps the timeout ought to be increased.<p>
     * 
     * If the application does not expect more requests and wishes to maintain
     * an outbound connection with the client used for interim responses
     * stretched over a long time or any other type of uni-directional
     * streaming, simply call {@link ClientChannel#shutdownInput()} which
     * effectively stops the timer.
     * 
     * @return read timeout duration (default is one and a half minute)
     * @see #timeoutResponse()
     */
    Duration timeoutRead();
    
    /**
     * Max duration allowed for the {@link ClientChannel} to await a
     * response.<p>
     * 
     * Assuming that the write stream is still open when the timeout occurs, a
     * {@link ResponseTimeoutException} will be delivered to the error
     * handler(s), the {@linkplain ErrorHandler#DEFAULT default} of which
     * translates it to a {@linkplain Responses#serviceUnavailable() 503
     * (Service Unavailable)}.<p>
     * 
     * The timer is only active while the client channel is expecting to receive
     * a response and while the server's response body subscriber has
     * unfulfilled outstanding demand. The first time the timer is activated is
     * when the request invocation chain completes and the last request body
     * bytebuffer has been released, and so the response will never time out
     * while a request is still actively being received or processed.<p>
     * 
     * A response producer that needs more time can reset the timer by sending a
     * 1XX (Informational) interim response or any other type of heartbeat.<p>
     * 
     * The timer is not active while a response is being transmitted on the
     * wire, this is covered by {@link #timeoutWrite()}.
     * 
     * @return response timeout duration (default is one and a half minute)
     */
    Duration timeoutResponse();
    
    /**
     * Max duration allowed for a channel write operation to complete.<p>
     * 
     * Analogous to the built-in protection against slow clients when receiving
     * data, this timer will cause the underlying channel write operation to
     * abort for response body bytebuffers not fully sent before the duration
     * elapses. The exception will not be delivered to the error handler(s) and
     * instead cause the channel to close immediately.<p>
     * 
     * The application can choose to publish very large response body
     * bytebuffers without worrying about a possible timeout due to the
     * increased time it may take to send a large buffer. The server will
     * internally slice the buffer [if need be] to match the read-operation's
     * buffer size.
     * 
     * @return write timeout duration (default is one and a half minute)
     * @see #timeoutRead()
     */
    Duration timeoutWrite();
    
    /**
     * If {@code true} (which is the default), any {@link Route} not
     * implementing the {@value HttpConstants.Method#OPTIONS} method will get
     * one.<p>
     * 
     * This works in the following way: As with any HTTP method, if the
     * implementation is missing, a {@link MethodNotAllowedException} is thrown
     * which may eventually reach the {@linkplain  ErrorHandler#DEFAULT default
     * error handler}. This handler in turn will - if this configuration is
     * enabled - respond a <i>successful</i> 204 (No Content). Had this
     * configuration not been enabled, the response would have been a <i>client
     * error</i> 405 (Method Not Allowed). In both cases, the {@value
     * HttpConstants.HeaderName#ALLOW} header will be set and populated with all
     * the HTTP methods that are indeed implemented. So there's really no other
     * difference between the two outcomes, other than the status code.<p>
     * 
     * With or without this configuration enabled, the application can easily
     * add its own {@code OPTIONS} implementation to the route or override the
     * default error handler.
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
         * @see Config#timeoutRead()
         */
        Builder timeoutRead(Duration newVal);
        
        /**
         * Set a new value.
         * 
         * The value can be any duration, although a too short (or negative)
         * duration will effectively make the server useless.
         * 
         * @param newVal new value
         * @return a new builder representing the new state
         * @throws NullPointerException if {@code newVal} is {@code null}
         * @see Config#timeoutResponse()
         */
        Builder timeoutResponse(Duration newVal);
        
        /**
         * Set a new value.
         * 
         * The value can be any duration, although a too short (or negative)
         * duration will effectively make the server useless.
         * 
         * @param newVal new value
         * @return a new builder representing the new state
         * @throws NullPointerException if {@code newVal} is {@code null}
         * @see Config#timeoutWrite()
         */
        Builder timeoutWrite(Duration newVal);
        
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