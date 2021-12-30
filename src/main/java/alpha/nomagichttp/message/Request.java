package alpha.nomagichttp.message;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.ReceiverOfUniqueRequestObject;
import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.util.Publishers;

import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousByteChannel;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.RandomAccess;
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
 * there is a body present, and it is not consumed then it will be silently
 * discarded as late in the HTTP exchange process as possible, which is when the
 * server's {@link Response#body() response body} subscription completes.<p>
 * 
 * The implementation is thread-safe, non-blocking and shallowly immutable.
 * Collaborating components too are thread-safe, but not necessarily immutable.
 * E.g. attribute entries and caching layers such as the lazy processing of path
 * parameters, the query string and header parsing.<p>
 * 
 * The request object does not implement {@code hashCode()} and {@code
 * equals()}. Its identity is unique per receiver (see {@link
 * ReceiverOfUniqueRequestObject}).
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Route
 * @see <a href="https://tools.ietf.org/html/rfc7230#section-3.1.1">RFC 7230 §3.1.1</a>
 * @see <a href="https://tools.ietf.org/html/rfc7230#section-3.3">RFC 7230 §3.3</a>
 */
public interface Request extends HeaderHolder, AttributeHolder
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
     * @return the request-line's method token
     *         (never {@code null}, empty or blank)
     * 
     * @see HttpConstants.Method
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
     * The returned value is a complex type of "/where?q=now#fragment". The raw
     * string value can be retrieved using {@code target().}{@link Target#raw()
     * raw()}.
     * 
     * @return the request-line's resource-target
     */
    Target target();
    
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
     * The returned version is "HTTP/1.1".
     * 
     * @return the request-line's HTTP version
     */
    HttpConstants.Version httpVersion();
    
    @Override
    Request.Headers headers();
    
    /**
     * Returns a body API object bound to this request.
     * 
     * @return a body API object bound to this request
     * 
     * @see Body
     */
    Body body();
    
    /**
     * Returns trailing headers.<p>
     * 
     * The client can append a header section after the message body. Apart from
     * the placement, these so-called trailing headers are identical to the more
     * commonly known header fields that occur before the message body.<p>
     * 
     * Trailing headers are good for sending metadata that was not available
     * before the body started transmitting. For example, appending a hash that
     * the receiver can use to verify message integrity.<p>
     * 
     * For the returned stage to complete, a non-empty request body must first
     * be consumed. If the body is empty, the returned stage will already be
     * completed with an empty headers object.<p>
     * 
     * RFC 7230 §4.4 defines an HTTP header "Trailer" which it says ought to
     * list what trailers will be sent after the body. The RFC also suggest that
     * the server move trailers to the "existing header fields" and then remove
     * the "Trailer" header, i.e. make it look like trailing headers are normal
     * headers (asynchronous APIs were not so common at the time!). And to
     * preempt the damage that may result from hoisting trailers, the RFC also
     * lists field names that it says must not be used as a trailing header, e.g.
     * "Host" and "Content-Length".<p>
     * 
     * The NoMagicHTTP does none of this stuff. The "Trailer" header and all
     * trailers will remain untouched. However, the RFC hack may be applied by
     * HTTP intermediaries who buffer up and de-chunk an HTTP/1.1 message before
     * forwarding it. The application can therefore not generally be sure that
     * the "Trailer" header and trailers are received in the same manner they
     * were sent. Unless the application has knowledge or make assumptions
     * about the request chain, it ought to fall back and look for missing
     * trailers amongst the ordinary HTTP headers.<p>
     * 
     * Trailing headers were first introduced in HTTP/1.1 chunked encoding (
     * <a href="https://tools.ietf.org/html/rfc7230#section-4.1.2">RFC 7230 §4.1.2</a>)
     * and although HTTP/2 discontinues chunked encoding in favor of its own
     * data frames, trailing headers remain supported (
     * <a href="https://tools.ietf.org/html/rfc7540#section-8.1">RFC 7540 §8.1</a>).
     * For requests of an older HTTP version ({@literal <} 1.1), this method
     * returns an already completed stage with an empty headers object.<p>
     * 
     * For more information on HTTP/1.1 chunked encoding, see {@link
     * HttpServer}.
     * 
     * The returned stage may be a copy. It can not be cast to a {@code
     * CompletableFuture} and then used to abort processing trailers.
     * 
     * @return trailing headers (never {@code null})
     */
    CompletionStage<BetterHeaders> trailers();
    
    /**
     * An API to access segments, path parameters (interpreted segments), query
     * key/value pairs and a fragment from the resource-target of a request.<p>
     * 
     * Path parameters come in two forms; single- and catch-all. The former is
     * required in order for the action/handler to have been matched, the latter
     * is optional but the server will make sure the value is always present and
     * begins with a '/'. Query parameters are always optional. Read more in
     * {@link Route}.<p>
     * 
     * A query value will be assumed to end with a space, ampersand ('&amp;') or
     * number sign ('&#35;') character. In particular, please note that the
     * semicolon ('&#59;') has no special meaning; it will <i>not</i> be
     * processed as a separator (contrary to
     * <a href="https://www.w3.org/TR/1999/REC-html401-19991224/appendix/notes.html#h-B.2.2">W3</a>,
     * we argue that magic is the trouble).<p>
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
     * splitting and parsing the value with whatever delimiting character one
     * choose is pretty straight forward:
     * 
     * <pre>{@code
     *     // "?numbers=1,2,3"
     *     // WARNING: This ignores the presence of repeated entries
     *     String[] vals = request.target()
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
     *     int[] ints = request.target()
     *                         .queryStream("number")
     *                         .mapToInt(Integer::parseInt)
     *                         .toArray();
     * }</pre>
     * 
     * Methods that does not carry the "raw" suffix will URL decode (aka.
     * percent-decode, aka. escape) the tokens (segment values, query keys,
     * path- and query parameter values) as if using
     * {@link URLDecoder#decode(String, Charset) URLDecoder.decode(segment,
     * StandardCharsets.UTF_8)} <i>except</i> the plus sign ('+') is <i>not</i>
     * converted to a space character and remains the same. If this is not
     * desired, use methods that carries the suffix "raw". The raw version is
     * useful when need be to unescape values using a different strategy, for
     * example when receiving a query string from a browser submitting an HTML
     * form using the "GET" method. The default encoding the browser uses will
     * be
     * <a href="https://www.w3.org/TR/html401/interact/forms.html#h-17.13.4.1">
     * application/x-www-form-urlencoded</a> which escapes space characters
     * using the plus character ('+').
     * 
     * <pre>{@code
     *     // Note: key needs to be in its raw form
     *     String nondecoded = request.target().queryFirstRaw("q");
     *     // '+' is replaced with ' '
     *     String formdata = java.net.URLDecoder.decode(nondecoded, StandardCharsets.UTF_8);
     * }</pre>
     * 
     * The implementation is thread-safe and non-blocking.
     * 
     * @author Martin Andersson (webmaster at martinandersson.com)
     */
    interface Target
    {
        /**
         * Returns the raw resource-target, e.g. "/where?q=now#fragment".
         * 
         * @return the raw resource-target (never {@code null}, empty or blank)
         */
        String raw();
        
        /**
         * Returns normalized and escaped path segment values.
         * 
         * The root ("/") is not represented in the returned list. If the parsed
         * request path was empty or effectively a single "/", then the returned
         * list will also be empty.<p>
         * 
         * The returned list is unmodifiable and implements {@link RandomAccess}.
         * 
         * @return normalized and escaped segment values
         * @see Route
         */
        List<String> segments();
        
        /**
         * Returns normalized but unescaped segment values.<p>
         * 
         * The root ("/") is not represented in the returned list. If the parsed
         * request path was empty or effectively a single "/", then the returned
         * list will also be empty.<p>
         * 
         * The returned list is unmodifiable and implements {@link RandomAccess}.
         * 
         * @return normalized but unescaped segment values
         * @see Route
         */
        List<String> segmentsRaw();
        
        /**
         * Returns a path parameter value (percent-decoded).<p>
         * 
         * Suppose that the HTTP server has a route registered which accepts a
         * parameter "who":
         * <pre>
         *   /hello/:who
         * </pre>
         * 
         * Given this request:
         * <pre>
         *   GET /hello/John%20Doe HTTP/1.1
         *   ...
         * </pre>
         * 
         * {@code request.target().pathParam("who")} returns "John Doe".<p>
         * 
         * Path parameters are not optional and so this method never returns the
         * empty string. {@code null} is only returned if the given name is
         * different from what the route declared.
         * 
         * @param name of path parameter (case-sensitive)
         * @return the path parameter value (percent-decoded)
         * @throws NullPointerException if {@code name} is {@code null}
         */
        String pathParam(String name);
        
        /**
         * Returns a map of all path parameters (percent-decoded).
         * 
         * The returned map has no defined iteration order and is unmodifiable.
         * 
         * @return a map of all path parameters (percent-decoded)
         */
        Map<String, String> pathParamMap();
        
        /**
         * Returns a raw path parameter value (not percent-decoded).<p>
         * 
         * Path parameters are not optional and so this method never returns the
         * empty string. {@code null} is only returned if the given name is
         * different from what the route declared.
         * 
         * @param name of path parameter (case-sensitive)
         * @return the raw path parameter value (not percent-decoded)
         * @throws NullPointerException if {@code name} is {@code null}
         * @see #pathParam(String) 
         */
        String pathParamRaw(String name);
        
        /**
         * Returns a map of all path parameters (not percent-decoded).
         * 
         * The returned map has no defined iteration order and is unmodifiable.
         * 
         * @return a map of all path parameters (not percent-decoded)
         */
        Map<String, String> pathParamRawMap();
        
        /**
         * Returns a query parameter value (first occurrence,
         * percent-decoded).<p>
         * 
         * Given this request:
         * <pre>{@code
         *   GET /hello?who=John%20Doe&who=other HTTP/1.1
         *   ...
         * }</pre>
         * 
         * {@code request.target().queryFirst("who")} will return "John Doe".
         * 
         * @param key of query parameter (case sensitive, not encoded/escaped)
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
         * decoded/unescaped).
         * 
         * @param keyRaw of query parameter (case sensitive, encoded/escaped)
         * @return the raw query parameter value (not decoded/unescaped)
         * @throws NullPointerException if {@code keyRaw} is {@code null}
         * 
         * @see #queryFirst(String)
         */
        Optional<String> queryFirstRaw(String keyRaw);
        
        /**
         * Returns a new stream of query parameter values (percent-decoded).<p>
         * 
         * The returned stream's encounter order follows the order in which
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
         * @see Target
         */
        Stream<String> queryStream(String key);
        
        /**
         * Returns a new stream of raw query parameter values (not
         * decoded/unescaped).<p>
         * 
         * The returned stream's encounter order follows the order in which
         * repeated query keys appeared in the client-provided query string.
         * 
         * @param keyRaw of query parameter (case sensitive, encoded/escaped)
         * 
         * @return a new stream of raw query parameter values (not
         *         decoded/unescaped)
         * 
         * @throws NullPointerException if {@code keyRaw} is {@code null}
         * 
         * @see Target
         */
        Stream<String> queryStreamRaw(String keyRaw);
        
        /**
         * Returns query parameter values (percent-decoded).<p>
         * 
         * The returned list's iteration order follows the order in which the
         * repeated query keys appeared in the client-provided query string.<p>
         * 
         * The returned list is unmodifiable and implements {@link RandomAccess}.
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
         * @see Target
         */
        List<String> queryList(String key);
        
        /**
         * Returns raw query parameter values (not decoded/unescaped).<p>
         * 
         * The returned list's iteration order follows the order in which the
         * repeated query keys appeared in the client-provided query string.<p>
         * 
         * The returned list is unmodifiable and implements {@link RandomAccess}.
         * 
         * @param keyRaw of query parameter (case sensitive, encoded/escaped)
         * 
         * @return an unmodifiable list of raw query parameter values (not
         *         decoded/unescaped)
         * 
         * @throws NullPointerException if {@code keyRaw} is {@code null}
         * 
         * @see Target
         */
        List<String> queryListRaw(String keyRaw);
        
        /**
         * Returns an unmodifiable map of query key to parameter values
         * (percent-decoded).<p>
         * 
         * The returned map's iteration order follows the order in which the
         * query keys appeared in the client-provided query string. Same is true
         * for the associated list of the values.
         * 
         * @return an unmodifiable map of query key to parameter values
         *         (percent-decoded)
         * 
         * @throws IllegalArgumentException
         *             if the decoder encounters illegal characters
         * 
         * @see Target
         */
        Map<String, List<String>> queryMap();
        
        /**
         * Returns an unmodifiable map of raw query key to raw parameter values
         * (not decoded/escaped).<p>
         * 
         * The returned map's iteration order follows the order in which the
         * query keys appeared in the client-provided query string. Same is true
         * for the associated list of the values.
         * 
         * @return an unmodifiable map of raw query key to raw parameter values
         *         (not decoded/escaped)
         * 
         * @see Target
         */
        Map<String, List<String>> queryMapRaw();
        
        /**
         * Returns the fragment of the resource-target.<p>
         * 
         * Given the resource-target "/where?q=now#fragment", this method
         * returns "fragment".<p>
         * 
         * The fragment is "dereferenced solely by the user agent" (
         * <a href="https://tools.ietf.org/html/rfc3986#section-3.5">RFC 3986 §3.5</a>)
         * and so shouldn't have been sent to the HTTP server in the first
         * place.<p>
         * 
         * If the fragment isn't present, this method returns the empty string.
         * 
         * @return the fragment of the resource-target (never {@code null})
         */
        String fragment();
    }
    
    /**
     * Request-specific headers.
     */
    interface Headers extends ContentHeaders {
        /**
         * Parses all "Accept" values.
         * 
         * @return parsed values (unmodifiable, {@link RandomAccess}, not {@code null})
         * 
         * @throws BadHeaderException
         *             if parsing failed (the cause is set to a
         *             {@link MediaTypeParseException})
         * 
         * @see HttpConstants.HeaderKey#ACCEPT
         * @see MediaType#parse(String) 
         */
        List<MediaType> accept();
    }
    
    /**
     * Is a thread-safe and non-blocking API for accessing the request body in
     * various forms.<p>
     * 
     * High-level methods (for example, {@link #toText()}), returns a {@link
     * CompletionStage} because the request handler will be called immediately
     * after the server is done parsing the request head. At this point in time,
     * not all bytes will necessarily have made it through on the network.<p>
     * 
     * The body bytes may be converted into any arbitrary Java type using the
     * {@link #convert(BiFunction)} method or consumed directly "on arrival"
     * using the {@link #subscribe(Flow.Subscriber)} method.<p>
     * 
     * If the body {@link #isEmpty()}; {@code subscribe()} completes the
     * subscription immediately. {@code toText()} completes immediately with an
     * empty string. {@code toFile()} completes immediately with 0 bytes. {@code
     * convert()} immediately invokes its given function with an empty byte
     * array. {@code subscribe(Flow.Publisher)} will delegate to {@link
     * Publishers#empty()}<p>
     * 
     * The body bytes can not be directly consumed more than once; they are not
     * saved by the server. An attempt to {@code convert()} or {@code
     * subscribe()} more than once will signal an {@code IllegalStateException}
     * to the subscriber.<p>
     * 
     * Same is true for utility methods that trickle down. If for example
     * {@code convert()} is used followed by {@code toText()}, then the latter
     * will complete exceptionally with an {@code IllegalStateException}.<p>
     * 
     * And, it does not matter if {@code Flow.Subscription.cancel()} is called
     * after the subscription has effectively started but before actually
     * consuming bytes. A subsequent subscriber will still be signalled the
     * {@code IllegalStateException}. The only exception is if a subscriber
     * cancels the subscription synchronously from {@code onSubscribe} which
     * will cause the subscription to roll back. This has the same effect as if
     * the subscription request was never made.<p>
     * 
     * Some utility methods such as {@code toText()} cache the result and will
     * return the same stage on future invocations.<p>
     * 
     * The normal way to reject an operation is to fail-fast and blow up the
     * calling thread. This is also common practice for rejected
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
     * occurs before a real subscription is made and will leave the channel's
     * read stream open. The application can generally attempt a new operation
     * to recover the body (e.g. {@code toText() >} {@code
     * IllegalCharsetNameException}). Unexpected errors - in particular, errors
     * that originate from the channel's read operation - have used up a real
     * subscription and also closed the read stream (e.g. {@code
     * RequestBodyTimeoutException}). Before attempting to recover the body,
     * always check first if the {@link ClientChannel#isOpenForReading()}.
     * 
     * 
     * <h2>Subscribing to bytes with a {@code Flow.Subscriber}</h2>
     * 
     * Almost all of the same {@code Flow.Publisher} semantics specified in the
     * JavaDoc of {@link Publishers} applies to the {@code Body} as a publisher
     * as well. The only exception is that the body does not support subscriber
     * reuse, again because the server does not keep the bytes after
     * consumption. Subscribing more than once will cause an {@link
     * IllegalStateException} to be signalled.<p>
     * 
     * The subscriber will receive bytebuffers in the same order they are read
     * from the underlying channel. The subscriber can not read beyond the
     * message/body boundary because the server will complete the subscription
     * before then and if need be, limit the last bytebuffer.
     * 
     * <h3>Releasing</h3>
     * 
     * The published bytebuffers are pooled and may be processed synchronously
     * or asynchronously. Whenever the application has finished processing
     * a bytebuffer, it must be {@link PooledByteBufferHolder#release()
     * released} which is a signal to the upstream publisher that the bytebuffer
     * may be reused for new operations. The thread doing the release may be
     * used to immediately publish new bytebuffers to the subscriber.<p>
     * 
     * Releasing the bytebuffer with bytes remaining to be read will cause the
     * bytebuffer to immediately become available for re-publication (ahead of
     * other bytebuffers already available). To reduce the chattiness, the
     * subscriber is encouraged to consume all remaining bytes before
     * releasing.<p>
     * 
     * Cancelling the subscription does not cause an outstanding bytebuffer to
     * be released. Releasing has to be done explicitly, or implicitly through
     * an exceptional return of {@code Subscriber.onNext()}.
     * 
     * <h3>Processing bytebuffers</h3>
     * 
     * The subscriber may request/demand any number of bytebuffers, but will
     * only receive the next bytebuffer after the previous one has been
     * released. So, awaiting more buffers before releasing old ones will
     * inevitably result in a {@link RequestBodyTimeoutException}.<p>
     * 
     * For the Body API to support concurrent processing of many
     * bytebuffers without the risk of adding unnecessary delays or blockages,
     * the API would have to declare methods that the application can use to
     * learn how many bytebuffers are immediately available and possibly also
     * learn the size of the upstream bytebuffer pool.<p>
     * 
     * This is not very likely to ever happen for a number of reasons. The API
     * complexity would increase dramatically for a doubtful benefit. In fact,
     * concurrent processing would most likely corrupt byte order and message
     * integrity.<p>
     * 
     * If there is a need for the application to "collect" or buffer the
     * bytebuffers, then this is a sign that the application's processing code
     * would have blocked the request thread (see "Threading Model" in {@link
     * HttpServer}). For example, {@link GatheringByteChannel} expects a {@code
     * ByteBuffer[]} but is a blocking API. In this particular case, the
     * solution is instead to submit the bytebuffers one at a time to {@link
     * AsynchronousByteChannel}, releasing each in the completion handler.<p>
     * 
     * Given how only one bytebuffer at a time is published then there's no
     * difference between requesting {@code Long.MAX_VALUE} versus requesting
     * one at a time. Unfortunately, the
     * <a href="https://github.com/reactive-streams/reactive-streams-jvm/blob/v1.0.3/README.md">
     * Reactive Streams</a> calls the latter approach an "inherently inefficient
     * 'stop-and-wait' protocol". In our context, this is wrong. For starters,
     * there's no stop. Furthermore, the bytebuffers are pooled and cached
     * upstream already. Requesting a bytebuffer is essentially the same as
     * polling a stupidly fast queue of buffers. The advantage of requesting
     * {@code Long.MAX_VALUE} is implementation simplicity.<p>
     * 
     * The channel uses <i>direct</i> bytebuffers in order to support
     * "zero-copy" transfers. I.e., the channel itself does not move data into
     * Java heap space. Whenever possible, pass forward the bytebuffers to the
     * next destination without reading the bytes in application code.<p>
     * 
     * There may be one or many processors installed between the channel and the
     * body publisher, which does not use direct bytebuffers. For example, an
     * HTTP/1.1 chunked decoder is publishing [decoded] bytebuffers allocated on
     * the heap. If the application uses {@link ByteBuffer#array()} to access
     * the bytes directly, do not forget to also set the new {@link
     * ByteBuffer#position(int)} before releasing it. Reading remaining bytes
     * directly but not updating the bytebuffer's position will cause an endless
     * loop where the upstream re-releases the same bytebuffer over and over
     * again.<p>
     * 
     * TODO: 1) Implement exception if released without consumption 100x in
     * a row, then the last sentence in this paragraph can be removed).
     * 
     * TODO: 2) Make discard() non-static and reference above. Good way to
     * update position and release.
     * 
     * <h3>The HTTP exchange and body discarding</h3>
     * 
     * The HTTP exchange is considered done as soon as 1) the request handler
     * invocation has returned, and 2) the request body subscription completes,
     * and 3) the final response body subscription completes. Not until then
     * will the next HTTP message-exchange commence on the same channel.<p>
     * 
     * This means that a request body subscriber should ensure his subscription
     * runs all the way to the end or is cancelled. Failure to request items in
     * a timely manner will result in a {@link RequestBodyTimeoutException}.<p>
     * 
     * But the application does not have to consume the body explicitly. When
     * the server's final response body subscription completes and earliest at
     * that point no request body subscriber has arrived, then the server will
     * assume that the body was intentionally ignored and proceed to discard it
     * - after which it can not be subscribed to by the application anymore.<p>
     * 
     * If a final response must be sent back immediately but reading the request
     * body bytes must be delayed, then there's at least two ways of solving
     * this. Either register a request body subscriber but delay requesting
     * items, or delay completing the server's response body subscription. Both
     * approaches are still subject to {@link
     * Config#timeoutIdleConnection()}. There is currently no API support to
     * temporarily suspend timeouts.
     * 
     * <h3>Exception Handling</h3>
     * 
     * The {@code Body} as a publisher follows the same exception semantics
     * specified in the JavaDoc of {@link Publishers}.<p>
     * 
     * If an exception propagating from {@code
     * Flow.Subscriber.onSubscribe()/onNext()/onComplete()} is observed by the
     * HTTP server's request thread, then standard {@link ErrorHandler error
     * handling} is kicked off.<p>
     * 
     * An exception signalled to {@code Subscriber.onError()} can safely be
     * assumed to indicate a low-level problem with the underlying channel or
     * JVM. The exception will have been logged by the HTTP server followed by
     * read-stream closure. These exceptions should generally be handled by
     * responding a 500 (Internal Server Error). Exceptions from {@code
     * onError()} itself, does not propagate anywhere.<p>
     * 
     * It's never a good design to rely on the error handler to resolve
     * errors thrown by the subscriber, as these can be lost and unobserved by
     * the server. The subscriber should never throw an exception.<p>
     * 
     * On the other hand, handling exceptions explicitly is not really necessary
     * when transforming the request body stage into a response stage passed to
     * the client channel. In this case, the application code is not the end
     * consumer, it's merely declaring transformations, and upstream errors as
     * well as intermittent stage errors will trickle down to the response
     * stage, which is observed by the server and consequently passed to the
     * error handler.
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
         * where {@code opts} is a {@code Set} containing the options specified
         * to this method.
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
         *       AsynchronousFileChannel.open}(file, opts, (ExecutorService) null, attrs);
         * </pre>
         * 
         * ...except if {@code options} is empty, a set of {@code WRITE} and
         * {@code CREATE_NEW} will be used (if the file exists the operation
         * will fail).<p>
         * 
         * If the returned stage completes with 0 bytes, then the file will not
         * have been created. If the file is created but the operation completes
         * exceptionally, then the file is removed.<p>
         * 
         * All exceptions thrown by {@code AsynchronousFileChannel.open()} is
         * delivered through the returned stage.
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
         * All body bytes will be collected into a byte[]. Once the whole body
         * has been read, the byte[] will be passed to the specified function
         * together with a count of valid bytes that can be safely read from the
         * array (starting at index 0).<p>
         * 
         * For example, this is a naive implementation of {@link #toText()} that
         * use a hardcoded charset:
         * <pre>{@code
         *   BiFunction<byte[], Integer, String> f = (buf, count) ->
         *       new String(buf, 0, count, StandardCharsets.US_ASCII);
         *   CompletionStage<String> text = myRequest.body().convert(f);
         * }</pre>
         * 
         * @param f    byte[]-to-type converter
         * @param <R>  result type
         * 
         * @return the result from applying the function {@code f}
         */
        <R> CompletionStage<R> convert(BiFunction<byte[], Integer, R> f);
        
        /**
         * Returns {@code true} if the request contains no body, otherwise
         * {@code false}.
         * 
         * @return {@code true} if the request contains no body,
         *         otherwise {@code false}
         * 
         * @see Body
         */
        boolean isEmpty();
    }
}