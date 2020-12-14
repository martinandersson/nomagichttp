package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.util.Publishers;

import java.net.URLDecoder;
import java.net.http.HttpHeaders;
import java.nio.channels.AsynchronousByteChannel;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.NetworkChannel;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.function.BiFunction;
import java.util.stream.Stream;

/**
 * An inbound HTTP request.<p>
 * 
 * The request handler is not required to consume the request or its body. If
 * there is a body present and it is not consumed then it will be silently
 * discarded as late in the HTTP exchange process as possible, which is when the
 * server's {@link Response#body() response body} subscription completes.<p>
 * 
 * The implementation is thread-safe and non-blocking.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Route
 * @see <a href="https://tools.ietf.org/html/rfc7230#section-3.1.1">RFC 7230 §3.1.1</a>
 * @see <a href="https://tools.ietf.org/html/rfc7230#section-3.3">RFC 7230 §3.3</a>
 */
public interface Request
{
    /**
     * Returns the request-line's method token.<p>
     * 
     * Given this request:
     * <pre>{@code
     *   GET /hello.txt HTTP/1.1
     *   User-Agent: curl/7.16.3 libcurl/7.16.3 OpenSSL/0.9.7l zlib/1.2.3
     *   Host: www.example.com
     *   Accept: text/plain;charset=utf-8
     * }</pre>
     * 
     * The returned value is "GET".
     * 
     * @return the request-line's method token (never {@code null} or empty)
     */
    String method();
    
    /**
     * Returns the request-line's resource-target.<p>
     * 
     * Given this request:
     * <pre>{@code
     *   GET /where?q=now#fragment HTTP/1.1
     *   User-Agent: curl/7.16.3 libcurl/7.16.3 OpenSSL/0.9.7l zlib/1.2.3
     *   Host: www.example.com
     *   Accept: text/plain;charset=utf-8
     * }</pre>
     * 
     * The returned value is "/where?q=now#fragment".<p>
     * 
     * The returned value is raw; not normalized and not URL decoded (aka.
     * percent-decoded). Decoded parameter values can be retrieved using {@link
     * Request#parameters()}. There is no API-support to retrieve the fragment
     * separately as it is "dereferenced solely by the user agent" (
     * <a href="https://tools.ietf.org/html/rfc3986#section-3.5">RFC 3986 §3.5</a>
     * ) and so shouldn't have been sent to the HTTP server in the first place.
     * 
     * @return the request-line's resource-target
     */
    String target();
    
    /**
     * Returns the request-line's HTTP version.<p>
     * 
     * Given this request:
     * <pre>{@code
     *   GET /hello.txt?query=value HTTP/1.1
     *   User-Agent: curl/7.16.3 libcurl/7.16.3 OpenSSL/0.9.7l zlib/1.2.3
     *   Host: www.example.com
     *   Accept: text/plain;charset=utf-8
     * }</pre>
     * 
     * The returned value is "HTTP/1.1".
     * 
     * @return the request-line's HTTP version
     */
    String httpVersion();
    
    /**
     * Returns a decoded path parameter value.<p>
     * 
     * Suppose that the HTTP server has a route registered which accepts a
     * parameter "who":<p>
     * <pre>
     *   /hello/{who}
     * </pre>
     * 
     * For the following request, {@code paramFromPath("who")} will return "John
     * Doe":
     * <pre>
     *   GET /hello/John%20Doe HTTP/1.1
     *   ...
     * </pre>
     * 
     * The returned value will be URL decoded (aka. percent-decoded) as if using
     * {@link URLDecoder#decode(String, Charset) URLDecoder.decode(segment,
     * StandardCharsets.UTF_8)} <i>except</i> the plus sign ('+') is <i>not</i>
     * converted to a space character and remains the same.<p>
     * 
     * The returned value is exactly what the client provided (after decoding).
     * I.e., there is no API support for so called "matrix variables" (nor are
     * they standardized). Such constructs can be implemented in application
     * code.<p>
     * 
     * @param name of path parameter (case sensitive)
     * 
     * @return the path parameter value
     * 
     * @throws NullPointerException
     *             if {@code name} is {@code null}
     * 
     * @throws IllegalArgumentException
     *             if the decoder encounters illegal characters
     */
    @Deprecated
    Optional<String> paramFromPath(String name);
    
    /**
     * Returns a raw path parameter value.<p>
     * 
     * An invocation of this method behaves in exactly the same way as the
     * invocation
     * <pre>
     *     request.{@link #paramFromPath(String) paramFromPath}(name);
     * </pre>
     * except without decoding the value.
     * 
     * @param name of path parameter (case sensitive)
     * 
     * @return the path parameter value
     * 
     * @throws NullPointerException if {@code name} is {@code null}
     */
    @Deprecated
    Optional<String> paramFromPathRaw(String name);
    
    /**
     * Returns a decoded query parameter value.<p>
     * 
     * For the following request, {@code paramFromQuery("who")} will return
     * "John Doe":
     * <pre>
     *   GET /hello?who=John%20Doe HTTP/1.1
     *   ...
     * </pre>
     * 
     * The returned value will be URL decoded (aka. percent-decoded) as if using
     * {@link URLDecoder#decode(String, Charset) URLDecoder.decode(segment,
     * StandardCharsets.UTF_8)} <i>except</i> the plus sign ('+') is <i>not</i>
     * converted to a space character and remains the same.<p>
     * 
     * @param name of query parameter (case sensitive)
     * 
     * @return the query parameter value
     * 
     * @throws NullPointerException
     *             if {@code name} is {@code null}
     * 
     * @throws IllegalArgumentException
     *             if the decoder encounters illegal characters
     */
    @Deprecated
    Optional<String> paramFromQuery(String name);
    
    /**
     * Returns a raw query parameter value.<p>
     * 
     * An invocation of this method behaves in exactly the same way as the
     * invocation
     * <pre>
     *     request.{@link #paramFromQuery(String) paramFromQuery}(name);
     * </pre>
     * except without decoding the value.<p>
     * 
     * This method is useful when need be to decode the query value manually,
     * for example when receiving data from a browser submitting an HTML form
     * using the "GET" method. The default encoding the browser uses will be
     * <a href="https://www.w3.org/TR/html401/interact/forms.html#h-17.13.4.1">
     * application/x-www-form-urlencoded</a> which escape space characters using
     * the plus character ('+').<p>
     * 
     * Example:
     * <pre>{@code
     *     String received = req.paramFromQueryRaw("name");
     *     String formdata = java.net.URLDecoder.decode(received, StandardCharsets.UTF_8);
     * }</pre>
     * 
     * @param name of query parameter (case sensitive)
     * 
     * @return the query parameter value
     * 
     * @throws NullPointerException if {@code name} is {@code null}
     */
    @Deprecated
    Optional<String> paramFromQueryRaw(String name);
    
    /**
     * Returns a parameters API bound to this request.<p>
     *
     * @return a parameters API bound to this request
     *
     * @see Parameters
     */
    Parameters parameters();
    
    /**
     * Returns the HTTP headers.
     * 
     * @return the HTTP headers
     * 
     * @see HttpHeaders
     */
    HttpHeaders headers();
    
    /**
     * Returns a body API bound to this request.<p>
     * 
     * @return a body API bound to this request
     * 
     * @see Body
     */
    Optional<Body> body();
    
    /**
     * Returns the channel from which this request originates.
     * 
     * @return the channel from which this request originates (never {@code null})
     */
    NetworkChannel channel();
    
    /**
     * Returns {@code true} if the channel from which this request originates is
     * open for reading, otherwise {@code false}.
     * 
     * @return see JavaDoc
     */
    boolean channelIsOpenForReading();
    
    /**
     * Is a thread-safe and non-blocking API for accessing request path- and
     * query parameter values.<p>
     * 
     * Any client-given request path (a component of {@link Request#target()}
     * may contain segments interpreted by the HTTP server as a path parameter
     * value and/or an URL search part, aka. query string (see {@link
     * Route}).<p>
     * 
     * Both query- and path parameters are optional and they can not be
     * specified as required. The request handler is free to interpret the
     * absence, presence and value of parameters however it sees fit.<p>
     * 
     * A path parameter value will be assumed to end with a space- or forward
     * slash character ('/').<p>
     * 
     * A query parameter value will be assumed to end with a space- or ampersand
     * ('&') character. In particular, please note that the semicolon (';') has
     * no special meaning; it will <i>not</i> be processed as a separator (
     * contrary to <a href="https://www.w3.org/TR/1999/REC-html401-19991224/appendix/notes.html#h-B.2.2">
     * W3</a>, we argue that magic is the trouble).<p>
     * 
     * The exact structure of the query string is not standardized (
     * <a href="https://en.wikipedia.org/wiki/Query_string">Wikipedia</a>). The
     * NoMagicHTTP library supports repeated query keys and query keys with no
     * value (will be mapped to the empty string "").<p>
     * 
     * Tokens (path parameter values, query keys/values) are not interpreted or
     * parsed by the HTTP server. In particular, please note that there is no
     * API-support for so called <i>path matrix variables</i> (nor is this magic
     * standardized) and appending brackets ("[]") to the query key has no
     * special meaning; it will simply become part of the query key itself.<p>
     * 
     * If embedding multiple query values into one key entry is desired, then
     * splitting and parsing the value with whatever delimiting character you
     * choose is pretty straight forward:
     * 
     * <pre>{@code
     *     // "?numbers=1,2,3"
     *     // WARNING: This ignores the presence of repeated entries
     *     String[] vals = request.parameters()
     *                            .queryFirst("numbers")
     *                            .get()
     *                            .split(",");
     *     int[] ints = Arrays.stream(vals)
     *                        .mapToInt(Integer::parseInt)
     *                        .toArray();
     * }</pre>
     * 
     * Instead of using a non-standardized separator, it's far more straight
     * forward and fool-proof (number separator would be dependent on regional
     * format) to rely on repetition instead:
     * 
     * <pre>{@code
     *     // "?number=1&number=2&number=3"
     *     int[] ints = request.parameters()
     *                         .queryStream("number")
     *                         .mapToInt(Integer::parseInt)
     *                         .toArray();
     * }</pre>
     * 
     * Methods in the Parameters API will normally URL decode (aka.
     * percent-decode) inbound tokens (query keys, path- and query parameter
     * values) as if using {@link URLDecoder#decode(String, Charset)
     * URLDecoder.decode(segment, StandardCharsets.UTF_8)} <i>except</i> the
     * plus sign ('+') is <i>not</i> converted to a space character and remains
     * the same. If this is not desired, use methods that carries the suffix
     * "raw". The raw version is useful when need be to unescape values using a
     * different strategy, for example when receiving a query string from a
     * browser submitting an HTML form using the "GET" method. The default
     * encoding the browser uses will be
     * <a href="https://www.w3.org/TR/html401/interact/forms.html#h-17.13.4.1">
     * application/x-www-form-urlencoded</a> which escapes space characters
     * using the plus character ('+').<p>
     * 
     * <pre>{@code
     *     // Note: key needs to be in its raw form
     *     String nondecoded = request.parameters().queryFirstRaw("q");
     *     // '+' is replaced with ' '
     *     String formdata = java.net.URLDecoder.decode(nondecoded, StandardCharsets.UTF_8);
     * }</pre>
     * 
     * @author Martin Andersson (webmaster at martinandersson.com)
     */
    interface Parameters
    {
        /**
         * Returns a path parameter value (percent-decoded).<p>
         * 
         * Suppose that the HTTP server has a route registered which accepts a
         * parameter "who":<p>
         * <pre>
         *   /hello/{who}
         * </pre>
         * 
         * Given this request:
         * <pre>
         *   GET /hello/John%20Doe HTTP/1.1
         *   ...
         * </pre>
         * 
         * {@code request.parameters().path("who")} will return "John Doe".
         * 
         * @param name of path parameter (case sensitive)
         * 
         * @return the path parameter value (percent-decoded)
         * 
         * @throws NullPointerException
         *             if {@code name} is {@code null}
         */
        Optional<String> path(String name);
        
        /**
         * Returns a raw path parameter value (not decoded/unescaped).
         * 
         * @param name of path parameter (case sensitive)
         * 
         * @return the raw path parameter value (not decoded/unescaped)
         * 
         * @throws NullPointerException if {@code name} is {@code null}
         * 
         * @see #path(String) 
         */
        Optional<String> pathRaw(String name);
        
        /**
         * Returns a query parameter value (first occurrence,
         * percent-decoded).<p>
         * 
         * Given this request:
         * <pre>
         *   GET /hello?who=John%20Doe&who=other HTTP/1.1
         *   ...
         * </pre>
         * 
         * {@code request.parameters().queryFirst("who")} will return "John
         * Doe".
         * 
         * @param key of query parameter (case sensitive, not encoded/escaped)
         * 
         * @return the query parameter value (first occurrence, percent-decoded)
         * 
         * @throws NullPointerException
         *             if {@code name} is {@code null}
         * 
         * @throws IllegalArgumentException
         *             if the decoder encounters illegal characters
         */
        Optional<String> queryFirst(String key);
        
        /**
         * Returns a raw query parameter value (first occurrence, not
         * decoded/unescaped).<p>
         * 
         * @param keyRaw of query parameter (case sensitive, encoded/escaped)
         * 
         * @return the raw query parameter value (not decoded/unescaped)
         * 
         * @throws NullPointerException if {@code keyRaw} is {@code null}
         * 
         * @see #queryFirst(String)
         */
        Optional<String> queryFirstRaw(String keyRaw);
        
        /**
         * Returns a new stream of query parameter values (percent-decoded).<p>
         * 
         * The returned stream's encounter order follows the order in which the
         * repeated query keys appeared in the client-provided query string.
         * 
         * @param key of query parameter (case sensitive, not encoded/escaped)
         * 
         * @return a new stream of query parameter values (percent-decoded)
         * 
         * @throws NullPointerException
         *             if {@code key} is {@code null}
         * 
         * @throws IllegalArgumentException
         *             if the decoder encounters illegal characters
         * 
         * @see Parameters
         */
        Stream<String> queryStream(String key);
        
        /**
         * Returns a new stream of raw query parameter values (not
         * decoded/unescaped).<p>
         * 
         * The returned stream's encounter order follows the order in which the
         * repeated query keys appeared in the client-provided query string.
         * 
         * @param keyRaw of query parameter (case sensitive, encoded/escaped)
         * 
         * @return a new stream of raw query parameter values (not
         *         decoded/unescaped)
         * 
         * @throws NullPointerException if {@code keyRaw} is {@code null}
         * 
         * @see Parameters
         */
        Stream<String> queryStreamRaw(String keyRaw);
        
        /**
         * Returns an unmodifiable list of query parameter values
         * (percent-decoded).<p>
         * 
         * The returned list's iteration order follows the order in which the
         * repeated query keys appeared in the client-provided query string.
         * 
         * @param key of query parameter (case sensitive, not encoded/escaped)
         * 
         * @return an unmodifiable list of query parameter values
         *         (percent-decoded)
         * 
         * @throws NullPointerException
         *             if {@code key} is {@code null}
         *
         * @throws IllegalArgumentException
         *             if the decoder encounters illegal characters
         * 
         * @see Parameters
         */
        List<String> queryList(String key);
        
        /**
         * Returns an unmodifiable list of raw query parameter values (not
         * decoded/unescaped).<p>
         * 
         * The returned list's iteration order follows the order in which the
         * repeated query keys appeared in the client-provided query string.
         * 
         * @param keyRaw of query parameter (case sensitive, encoded/escaped)
         * 
         * @return an unmodifiable list of raw query parameter values (not
         *         decoded/unescaped)
         * 
         * @throws NullPointerException if {@code keyRaw} is {@code null}
         * 
         * @see Parameters
         */
        List<String> queryListRaw(String keyRaw);
        
        /**
         * Returns an unmodifiable map of query key to parameter values
         * (percent-decoded).<p>
         * 
         * The returned map's iteration order follows the order in which the
         * query keys appeared in the client-provided query string. Same is true
         * for the associated list of the values.<p>
         * 
         * @return an unmodifiable map of query key to parameter values
         *         (percent-decoded)
         *
         * @throws IllegalArgumentException
         *             if the decoder encounters illegal characters
         * 
         * @see Parameters
         */
        Map<String, List<String>> queryMap();
        
        /**
         * Returns an unmodifiable map of raw query key to raw parameter values
         * (not decoded/escaped).<p>
         * 
         * The returned map's iteration order follows the order in which the
         * query keys appeared in the client-provided query string. Same is true
         * for the associated list of the values.<p>
         * 
         * @return an unmodifiable map of raw query key to raw parameter values
         *         (not decoded/escaped)
         * 
         * @see Parameters
         */
        Map<String, List<String>> queryMapRaw();
    }
    
    /**
     * Is a thread-safe and non-blocking API for accessing the request body in
     * various forms.<p>
     * 
     * High-level methods (for example, {@link #toText()}), returns a {@link
     * CompletionStage} because the request handler will be invoked and
     * therefore have access to the Body API immediately after the server is
     * done parsing the request head. At this point in time, not all bytes will
     * necessarily have made it through on the network.<p>
     * 
     * The body bytes may be converted into any arbitrary Java type using the
     * {@link #convert(BiFunction)} method or consumed directly "on arrival"
     * using the {@link #subscribe(Flow.Subscriber)} method.<p>
     * 
     * The body bytes can not be directly consumed more than once; they are not
     * saved by the server. An attempt to {@code convert(...)} or {@code
     * subscribe(...)} more than once will result in an {@code
     * IllegalStateException}.<p>
     * 
     * Same is is also true for utility methods that "trickle down". If for
     * example method {@code convert(...)} is used followed by {@code toText()},
     * then the latter will complete exceptionally with an {@code
     * IllegalStateException}.<p>
     * 
     * It does not matter if a {@code Flow.Subscription} is immediately
     * cancelled with or without actually consuming any bytes (application is
     * assumed to ignore the body, followed by a server-side discard of it).<p>
     * 
     * Some utility methods such as {@code toText()} cache the result and will
     * return the same stage on future invocations. This may for example be
     * useful to an {@link ErrorHandler error handler} also interested in
     * accessing the result.<p>
     * 
     * The normal way to reject an operation is to fail-fast and blow up the
     * calling thread. This is also common practice even for rejected
     * <i>asynchronous</i> operations. For example,
     * {@code ExecutorService.submit()} throws {@code
     * RejectedExceptionException} and {@code AsynchronousByteChannel.read()}
     * throws {@code IllegalArgumentException}.<p>
     * 
     * However - for good or bad - the <a
     * href="https://github.com/reactive-streams/reactive-streams-jvm/blob/v1.0.3/README.md">
     * Reactive Streams</a> specification mandates that all exceptions are
     * signalled through the subscriber. In order then to have a coherent API,
     * all exceptions produced by the Body API will be delivered through the
     * result carrier; whether that is a {@code CompletionStage} or a {@code
     * Flow.Subscriber}. This has the implication that exceptions from utility
     * methods are not documented using the standard {@code @throws} tag but
     * rather inline with the rest of the JavaDoc. The only exception to this
     * rule is {@code NullPointerException} which will blow up the calling
     * thread wherever warranted.<p>
     * 
     * In general, high-level exception types - in particular, when documented -
     * does not close the underlying channel's read stream and so the
     * application can choose to recover from them. The opposite is true for
     * unexpected errors, in particular, errors that originate from the
     * channel's read operation. The safest bet for an application when
     * attempting error recovery is to always check first if {@link
     * Request#channelIsOpenForReading()}.<p>
     * 
     * 
     * <h3>Subscribing to bytes with a {@code Flow.Subscriber}</h3>
     * 
     * Almost all of the same {@code Flow.Publisher} semantics specified in the
     * javadoc of {@link Publishers} applies to the {@code Body} as a publisher
     * as well. The only exception is that the body does not support subscriber
     * reuse, simply because the body can not be subscribed to more than
     * once.<p>
     * 
     * The subscriber will receive bytebuffers in the same order they are read
     * from the underlying channel. The subscriber can not read beyond the
     * message/body boundary because the server will complete the subscription
     * before then and if need be, limit the last bytebuffer.<p>
     * 
     * <h4>Releasing</h4>
     * 
     * The published bytebuffers are pooled and may be processed synchronously
     * or asynchronously. Whenever the application has finished processing
     * a bytebuffer, it must be {@link PooledByteBufferHolder#release()
     * released} which is a signal to the server that the bytebuffer may be
     * reused for new channel operations. The thread doing the release may be
     * used to immediately publish new bytebuffers to the subscriber.<p>
     * 
     * Releasing the bytebuffer with bytes remaining to be read will cause the
     * bytebuffer to immediately become available for re-publication (ahead of
     * other bytebuffers already available).<p>
     * 
     * Cancelling the subscription does not cause the bytebuffer to be released.
     * Releasing has to be done explicitly, or implicitly through an exceptional
     * return of {@code Subscriber.onNext()}.<p>
     * 
     * <h4>Processing bytebuffers</h4>
     * 
     * The subscriber may request/demand any number of bytebuffers, but will
     * only receive the next bytebuffer after the previous one has been
     * released. So, awaiting more buffers before releasing old ones will
     * inevitably halt progress.<p>
     * 
     * In order to fully support concurrent processing of many bytebuffers
     * without the risk of adding unnecessary delays or blockages, the Body API
     * would have to declare methods that the application can use to learn how
     * many bytebuffers are immediately available and possibly also learn the
     * size of the server's bytebuffer pool.<p>
     * 
     * This is not very likely to happen. Not only will concurrent processing be
     * a challenge for the application to implement properly with respect to
     * byte order and message integrity, but concurrent processing could also be
     * a sign that the application's processing code would have blocked the
     * request thread (see "Threading Model" in {@link HttpServer}) - hence the
     * need to "collect" or buffer the bytebuffers. For example, {@link
     * GatheringByteChannel} expects a {@code ByteBuffer[]} but is a blocking
     * API. Instead, submit the bytebuffers one at a time to {@link
     * AsynchronousByteChannel}, releasing each in the completion handler.<p>
     * 
     * Given how only one bytebuffer at a time is published, there's really no
     * difference between requesting {@code Long.MAX_VALUE} versus requesting
     * one at a time. Unfortunately, the Reactive Stream specification calls the
     * latter approach an "inherently inefficient 'stop-and-wait' protocol".
     * This is wrong. The bytebuffers are pooled and cached upstream already.
     * Requesting a bytebuffer is essentially the same as polling a stupidly
     * fast queue of buffers and there simply does not exist - surprise surprise
     * - a good reason to engage in "premature optimization".<p>
     * 
     * Speaking of optimization; the default implementation uses <i>direct</i>
     * bytebuffers in order to support "zero-copy" transfers. I.e., no data is
     * moved into Java heap space unless the subscriber itself causes this to
     * happen. Whenever possible, always pass forward the bytebuffers to the
     * destination without reading the bytes in application code!<p>
     * 
     * <h4>The HTTP exchange and body discarding</h4>
     * 
     * The HTTP exchange is considered done as soon as both the server's
     * response body subscription <i>and</i> the application's request body
     * subscription have both completed. Not until then will the next HTTP
     * message-exchange commence on the same channel.<p>
     * 
     * This means that a request body subscriber must ensure his subscription
     * runs all the way to the end or is cancelled. Subscribing to the body but
     * never completing the subscription may lead to a progress halt for the
     * underlying channel (there is no timeout in the NoMagicHTTP library
     * code).<p>
     * 
     * If the server's response body subscription completes and earliest at that
     * point no request body subscriber has arrived, then the server will assume
     * that the body was intentionally ignored and proceed to discard it - after
     * which it can not be subscribed to by the application any more.<p>
     * 
     * If a response must be sent back immediately but processing the
     * request body bytes must be delayed, then there's at least two ways of
     * solving this. Either delay completing the server's response body
     * subscription or register a request body subscriber but delay requesting
     * items.<p>
     * 
     * <h4>Exception Handling</h4>
     * 
     * The {@code Body} as a publisher follows the same exception semantics
     * specified in the JavaDoc of {@link Publishers}, decorated with some added
     * behavior on top.<p>
     * 
     * Exceptions thrown by the {@code Flow.Subscriber.onSubscribe()} method
     * propagates to the calling thread, i.e., the one calling {@code
     * Body.subscribe()}. If this thread is the HTTP server's request thread,
     * then standard {@link ErrorHandler error handling} is kicked off.<p>
     * 
     * Exceptions thrown by the subscriber's {@code onNext()} and {@code
     * onComplete()} methods will be logged by the server if the channel's read
     * stream is still open when the exceptional return occur. The server will
     * also close the read stream using a similar procedure documented in {@link
     * Response#mustCloseAfterWrite()} (the write stream remains untouched so
     * that a response in-flight can complete).<p>
     * 
     * Exceptions signalled to {@code Subscriber.onError()} that are not
     * <i>caused by</i> the subscriber itself can safely be assumed to indicate
     * low-level problems with the underlying channel. They will also have been
     * logged by the HTTP server followed suite by read-stream closure.<p>
     * 
     * Even if the server has a reaction for some observed exceptions - such as
     * closing the read stream as previously noted - this doesn't stop the
     * exception from being propagated as specified by {@link Publishers}.
     * 
     * @author Martin Andersson (webmaster at martinandersson.com)
     */
    interface Body extends Flow.Publisher<PooledByteBufferHolder>
    {
        /**
         * Convert the body to a string.<p>
         * 
         * The charset used for decoding will be taken from the request headers'
         * "Content-Type" media type parameter "charset", but only if the type
         * is "text". If this information is not present, then UTF-8 will be
         * used.<p>
         * 
         * Please note that UTF-8 is backwards compatible with ASCII and
         * required by all Java virtual machines to be supported.<p>
         * 
         * The returned stage is cached and subsequent invocations return the
         * same instance.<p>
         * 
         * Instead of this method throwing an exception, the returned stage may
         * complete exceptionally with (but not limited to):<p> 
         * 
         * {@link BadHeaderException}
         *     if headers has multiple Content-Type keys.<p>
         * {@link IllegalCharsetNameException}
         *     if the charset name is illegal (for example, "123").<p>
         * {@link UnsupportedCharsetException}
         *     if no support for the charset name is available in this instance
         *     of the Java virtual machine.
         * 
         * @return a stage that completes with the body as a string
         */
        CompletionStage<String> toText();
        
        // TODO: Mimic method signatures of BodyHandlers.ofFile. I.e., no-arg
        //       overload specifies default values and impl. crashes for
        //       "non-sensible" values.
        
        /**
         * Save the body to a file.<p>
         * 
         * An invocation of this method behaves in exactly the same way as the
         * invocation
         * <pre>
         *     body.{@link #toFile(Path,Set,FileAttribute[])
         *       toFile}(file, opts, new FileAttribute&lt;?&gt;[0]);
         * </pre>
         * where {@code opts} is a {@code Set} containing the options specified to
         * this method.
         * 
         * @param file to dump body into
         * @param options specifying how the file is opened
         * 
         * @return a stage that completes with the number of bytes written to file
         */
        CompletionStage<Long> toFile(Path file, OpenOption... options);
        
        /**
         * Save the body to a file.<p>
         * 
         * This method is equivalent to
         * <pre>
         *     {@link AsynchronousFileChannel#open(Path,Set,ExecutorService,FileAttribute[])
         *       AsynchronousFileChannel.open}(file, opts, null, attrs);
         * </pre>
         * 
         * ...except if {@code options} is empty, a set of {@code WRITE}, {@code
         * CREATE} and {@code TRUNCATE_EXISTING} will be used. I.e, by default,
         * a new file will be created or an existing file will be
         * overwritten.<p>
         * 
         * If the operation completes exceptionally, the file is removed.<p>
         *
         * All exceptions thrown by {@code AsynchronousFileChannel.open()} is
         * delivered through the returned stage.<p>
         * 
         * @param file    to dump body into
         * @param options specifying how the file is opened
         * @param attrs   an optional list of file attributes to set atomically
         *                when creating the file
         * 
         * @return a stage that completes with the number of bytes written to file
         */
        CompletionStage<Long> toFile(Path file, Set<? extends OpenOption> options, FileAttribute<?>... attrs);
        
        // TODO: toEverythingElse()
        
        /**
         * Convert the request body into an arbitrary Java type.<p>
         * 
         * All body bytes will be collected into a byte[]. Once all of the body
         * has been read, the byte[] will be passed to the specified function
         * together with a count of valid bytes that can be safely read from the
         * array.<p>
         * 
         * For example;
         * <pre>{@code
         *   BiFunction<byte[], Integer, String> f = (buf, count) ->
         *       new String(buf, 0, count, StandardCharsets.US_ASCII);
         *   
         *   CompletionStage<String> text = myRequest.body().convert(f);
         * }</pre>
         * 
         * @param f    byte[]-to-type converter
         * @param <R>  result type
         * 
         * @return the result from applying the function {@code f}
         */
        <R> CompletionStage<R> convert(BiFunction<byte[], Integer, R> f);
    }
}