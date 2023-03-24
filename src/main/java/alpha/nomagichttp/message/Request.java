package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.action.BeforeAction;
import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.util.ScopedValues;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.Buffer;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
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
import java.util.stream.Stream;

/**
 * An inbound HTTP request.<p>
 * 
 * The invoked entity may have been associated with a unique path pattern, which
 * affects path parameters available in the {@link Request#target()}. To support
 * this, the request object is unique per invoked entity. A {@link BeforeAction}
 * will receive a different instance than what the {@link RequestHandler}
 * receives. The only real difference will be the underlying reference to the
 * target component.<p>
 * 
 * For example, although request "/hello" matches before-action "/:foo" and
 * {@link Route} "/:bar", the former will have to use the key "foo" when
 * retrieving the segment value from the target and a request handler of the
 * latter will have to use the key "bar".<p>
 * 
 * As another example, suppose a before-action registers using the pattern
 * "/*seg" and there's also a route "/hello/:seg". For an inbound request
 * "/hello/world", the former's "seg" parameter will map to the value
 * "/hello/world" but the route's request handler will observe the value
 * "world" using the same key.<p>
 * 
 * All other components of the request object is shared by all request instances
 * created throughout the HTTP exchange, most importantly the request attributes
 * and body. Changes to these structures is visible across execution boundaries,
 * such as setting attributes and consuming the body bytes (which should be
 * done only once!).<p>
 * 
 * The implementation is mostly thread-safe. The exceptions are the
 * {@code Request.Body} and the {@link #trailers() trailers} method.<p>
 * 
 * Any operation on the request object that falls through to a channel read
 * operation (consuming body bytes or parsing trailers), and is performed after
 * the HTTP exchange has already completed, will throw an
 * {@link IllegalStateException}. The request should be processed by the request
 * thread executing the request processing chain, not asynchronously.<p>
 * 
 * Unless documented differently, this interface and its nested interfaces,
 * never accept a {@code null} argument nor return a {@code null} value.<p>
 * 
 * The request object does not implement {@code hashCode} and {@code equals}.
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
     *         (never empty or blank)
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
    Headers headers();
    
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
     * The request processing chain will be invoked immediately after the
     * request headers have been received, and trailers occur on the wire after
     * the body, therefore, one must consume the body before invoking this
     * method.<p>
     * 
     * The client can append a header section after the message body. Apart from
     * the placement, these so-called trailing headers are identical to the more
     * commonly known header fields that occur before the message body.<p>
     * 
     * Trailing headers are good for sending metadata that was not available
     * before the body started transmitting. For example, appending a hash that
     * the receiver can use to verify message integrity. Another example is
     * having the client provide metrics, such as a timestamp when the client
     * finished sending the body.<p>
     * 
     * RFC 7230 §4.4 defines an HTTP header "Trailer" which it says clients
     * should use to list what trailers will be sent after the body. The RFC
     * also suggest that the server move trailers to the "existing header
     * fields" and then remove the "Trailer" header, i.e. make it look like
     * trailing headers are normal headers (asynchronous APIs were not so common
     * at the time!). And to preempt the damage that may result from hoisting
     * trailers, the RFC also lists field names that it says must not be used as
     * a trailing header, e.g. "Host" and "Content-Length".<p>
     * 
     * The NoMagicHTTP does none of this stuff. The "Trailer" header and all
     * trailers will remain untouched. However, the RFC hack <i>may</i> be
     * applied by HTTP intermediaries who buffer up and dechunk an HTTP/1.1
     * message before forwarding it. The application can therefore not
     * generally be sure that the "Trailer" header and trailers are received in
     * the same manner they were sent. Unless the application has knowledge or
     * make assumptions about the request chain, it ought to fall back and look
     * for missing trailers amongst the ordinary HTTP headers.<p>
     * 
     * Trailing headers were first introduced in HTTP/1.1 chunked encoding (
     * <a href="https://tools.ietf.org/html/rfc7230#section-4.1.2">RFC 7230 §4.1.2</a>)
     * and although HTTP/2 discontinues chunked encoding in favor of its own
     * data frames, trailing headers remain supported (
     * <a href="https://tools.ietf.org/html/rfc7540#section-8.1">RFC 7540 §8.1</a>).
     * For requests of an older HTTP version ({@literal <} 1.1), this method
     * returns an empty headers object.<p>
     * 
     * The trailers are cached and this method can be called many times.
     * 
     * @return trailing headers
     * 
     * @throws IllegalStateException
     *             if the body has not been consumed
     * @throws MaxRequestTrailersSizeExceededException
     *             if the length of trailers exceeds the configured tolerance
     * @throws IOException
     *             if an I/O error occurs
     */
    BetterHeaders trailers() throws IOException;
    
    /**
     * An API to access segments, path parameters (interpreted segments), query
     * key/value pairs and a fragment from the resource-target of a request.<p>
     * 
     * Path parameters come in two forms; single- and catch-all. The former is
     * required in order for the action/handler to have been matched, the latter
     * is optional but the server will make sure the value is always present and
     * begins with a '/'. Query parameters are always optional. Read more in the
     * JavaDoc of {@link Route}.<p>
     * 
     * A query value will be assumed to end with a space, ampersand ('&amp;') or
     * number sign ('&#35;') character. In particular, note that the semicolon
     * ('&#59;') has no special meaning; it will <i>not</i> be processed as a
     * separator (contrary to
     * <a href="https://www.w3.org/TR/1999/REC-html401-19991224/appendix/notes.html#h-B.2.2">W3</a>,
     * we argue that magic is the trouble).<p>
     * 
     * The exact structure of the query string is not standardized (
     * <a href="https://en.wikipedia.org/wiki/Query_string">Wikipedia</a>). The
     * NoMagicHTTP library supports repeated query keys and query keys with no
     * value (will be mapped to the empty string).<p>
     * 
     * Tokens (path parameter values, query keys/values) are not interpreted or
     * parsed by the HTTP server. In particular, note that there is no
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
         * @return the raw resource-target (never empty or blank)
         */
        String raw();
        
        /**
         * Returns normalized and escaped path segment values.<p>
         * 
         * The root ("/") is not represented in the returned list. If the parsed
         * request path was empty or effectively a single "/", then the returned
         * list will also be empty.<p>
         * 
         * The returned list is unmodifiable and implements
         * {@link RandomAccess}.
         * 
         * @return normalized and escaped segment values 
         * 
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
         * The returned list is unmodifiable and implements
         * {@link RandomAccess}.
         * 
         * @return normalized but unescaped segment values
         * 
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
         * 
         * @return the path parameter value (percent-decoded)
         * 
         * @throws NullPointerException
         *             if {@code name} is {@code null}
         * 
         * @throws UnsupportedOperationException
         *             if called from an error handler
         *             (has not registered a path pattern)
         */
        String pathParam(String name);
        
        /**
         * Returns a map of all path parameters (percent-decoded).<p>
         * 
         * The returned map has no defined iteration order and is unmodifiable.
         * 
         * @return a map of all path parameters (percent-decoded)
         * 
         * @throws UnsupportedOperationException
         *             if called from an error handler
         *             (has not registered a path pattern)
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
         * 
         * @return the raw path parameter value (not percent-decoded)
         * 
         * @throws NullPointerException
         *             if {@code name} is {@code null}
         * 
         * @throws UnsupportedOperationException
         *             if called from an error handler
         *             (has not registered a path pattern)
         * 
         * @see #pathParam(String) 
         */
        String pathParamRaw(String name);
        
        /**
         * Returns a map of all path parameters (not percent-decoded).<p>
         * 
         * The returned map has no defined iteration order and is unmodifiable.
         * 
         * @return a map of all path parameters (not percent-decoded)
         * 
         * @throws UnsupportedOperationException
         *             if called from an error handler
         *             (has not registered a path pattern)
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
         * decoded/unescaped).
         * 
         * @param keyRaw of query parameter (case sensitive, encoded/escaped)
         * 
         * @return the raw query parameter value (not decoded/unescaped)
         * 
         * @throws NullPointerException
         *             if {@code keyRaw} is {@code null}
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
         * @return a new stream of raw query parameter values
         *         (not decoded/unescaped)
         * 
         * @throws NullPointerException
         *             if {@code keyRaw} is {@code null}
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
         * The returned list is unmodifiable and implements
         * {@link RandomAccess}.
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
         * @throws NullPointerException
         *             if {@code keyRaw} is {@code null}
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
         * @return the fragment of the resource-target
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
         * @return parsed values
         *         (unmodifiable, {@link RandomAccess})
         * 
         * @throws BadHeaderException
         *             if parsing failed (the cause is set to a
         *             {@link MediaTypeParseException})
         * 
         * @see HttpConstants.HeaderName#ACCEPT
         * @see MediaType#parse(String) 
         */
        List<MediaType> accept();
    }
    
    /**
     * Is an API for reading the request body in various forms.
     * 
     * <pre>{@code
     *   // Convert all bytes to String
     *   var string = request.body().toText();
     *   
     *   // Gather all bytes
     *   byte[] onHeap = request.body().bytes();
     *   
     *   // Classic iteration
     *   var it = request.body().iterator();
     *   while (it.hasNext()) {
     *       var byteBuffer = it.next();
     *       ...
     *   }
     *   
     *   // Functional iteration
     *   request.body().iterator()
     *                 .forEachRemaining(byteBuffer -> ...);
     * }</pre>
     * 
     * The request processing chain will be invoked immediately after the
     * request headers have been parsed, and it is likely that the body hasn't
     * yet been physically received on the wire. The body iterator is
     * <i>lazy</i> and will, if necessary, perform channel reads into the <i>one
     * and only</i> internally used bytebuffer that is then returned to the
     * consumer as a {@link ByteBuffer read-only} view.<p>
     * 
     * The buffer should be either partially or fully consumed at once. If the
     * buffer is not fully consumed (it has bytes {@link Buffer#remaining()
     * remaining}), then the next call to {@code next} (no pun intended) will
     * shortcut the channel read operation and simply return the same buffer
     * immediately.<p>
     * 
     * To ensure progress, the iteration <strong>must use relative get or
     * relative bulk get methods</strong> on the bytebuffer. Absolut methods can
     * be used to intentionally <i>peek</i> but not consume data.<p>
     * 
     * If the iteration uses {@link ByteBuffer#array()} to read bytes directly
     * (assuming the bytebuffer is backed by an array), one must also set the
     * new {@link ByteBuffer#position(int)}.<p>
     * 
     * The implementation does not cache bytes, and eventually, there will be no
     * more bytes remaining, and subsequent calls to {@code iterator} will
     * return an empty iterator.<p>
     * 
     * A non-finite streaming body will keep reading from the channel until
     * end-of-stream, at which point {@code next} returns an empty buffer.
     * Succeeding calls to {@code hasNext} returns false and succeeding calls to
     * {@code iterator} returns an empty iterator.<p>
     * 
     * The request handler is not required to consume the body. If there is a
     * body present, and it is not consumed, then it will be discarded at the
     * end of the HTTP exchange (after the request handler returns and after the
     * final response has been written).<p>
     * 
     * In fact, it is expected that the body is discarded if the response code
     * is an error code (4XX or 5XX). And, couple this with the fact that the
     * server may send interim responses, does mean that 98% of the internet is
     * blatantly wrong when it labels HTTP as a <i>synchronous one-to-one</i>
     * protocol!
     * 
     * <h2>Handling errors</h2>
     * 
     * In general, high-level exception types — in particular, when documented —
     * occurs before a channel read operation is made and will leave the
     * channel's input stream open. The application can generally attempt a new
     * operation to recover the body (for example, {@code toText() >} {@code
     * CharacterCodingException}).<p>
     * 
     * Errors that originate from the channel's read operation will have shut
     * down the input stream. Before attempting to recover the body, always
     * check first if
     * <pre>
     *   {@link ScopedValues#channel() channel
     *       }().{@link ClientChannel#isInputOpen() isInputOpen}()
     * </pre>
     * 
     * <h2>Direct bytebuffer</h2>
     * 
     * The channel uses a <i>direct</i> {@link ByteBuffer} to support
     * "zero-copy" transfers. That is to say, the Java virtual machines makes
     * a best effort to not move data into heap space. Whenever possible, pass
     * forward the bytebuffer to the next destination without reading the bytes
     * in application code.
     * 
     * @author Martin Andersson (webmaster at martinandersson.com)
     */
    interface Body extends ByteBufferIterable
    {
        /**
         * Converts the remaining body bytes to a char sequence.<p>
         * 
         * The charset used for decoding will be taken from the request headers'
         * "Content-Type" media type parameter "charset", but only if the type
         * is "text". If this information is not present, then UTF-8 will be
         * used.<p>
         * 
         * UTF-8 is backwards compatible with ASCII and required by all Java
         * virtual machines to be supported.<p>
         * 
         * Thee is no method overload that accepts a charset or a decoder,
         * because it isn't conceivable that many applications will need it.
         * However, if desired:
         * 
         * <pre>
         *     var charseq = myWeirdCharset.{@link Charset#newDecoder() newDecoder
         *     }().{@link CharsetDecoder#decode(ByteBuffer) decode
         *     }({@link ByteBuffer#wrap(byte[]) wrap}(body.{@link #bytes() bytes}()));
         * </pre>
         * 
         * Avoid using {@link Charset#decode(ByteBuffer)} as it will use
         * thread-locals to cache the decoder, which is not ideal for virtual
         * threads, nor is it determined whether caching a decoder yields a
         * performance improvement.
         * 
         * @throws BadHeaderException
         *             if headers has multiple Content-Type values
         * @throws IllegalCharsetNameException
         *             if the charset name is illegal (for example, "123")
         * @throws UnsupportedCharsetException
         *             if no support for the charset name is available in this
         *             instance of the Java virtual machine.
         * @throws UnsupportedOperationException
         *             if {@code length} is unknown
         * @throws BufferOverflowException
         *             if {@code length} is greater than
         *             {@code Integer.MAX_VALUE}
         * @throws CharacterCodingException
         *             if input is malformed, or
         *             if a character is unmappable
         * @throws MaxRequestBodyConversionSizeExceededException
         *             if the body size exceeds the internal buffer limit
         * @throws IOException
         *             if an I/O error occurs
         * 
         * @return the body as a char sequence
         */
        CharSequence toCharSequence() throws IOException;
        
        /**
         * Converts the remaining body bytes to a string.
         * 
         * @implSpec
         * The default implementation is equivalent to:
         * <pre>
         *   return {@link #toCharSequence() toCharSequence}().toString();
         * </pre>
         * 
         * @throws BadHeaderException
         *             if headers has multiple Content-Type values
         * @throws IllegalCharsetNameException
         *             if the charset name is illegal (for example, "123")
         * @throws UnsupportedCharsetException
         *             if no support for the charset name is available in this
         *             instance of the Java virtual machine.
         * @throws UnsupportedOperationException
         *             if {@code length} is unknown
         * @throws BufferOverflowException
         *             if {@code length} is greater than
         *             {@code Integer.MAX_VALUE}
         * @throws CharacterCodingException
         *             if input is malformed, or
         *             if a character is unmappable
         * @throws MaxRequestBodyConversionSizeExceededException
         *             if the body size exceeds the internal buffer limit
         * @throws IOException
         *             if an I/O error occurs
         * 
         * @return the body as a string
         */
        default String toText() throws IOException {
            return toCharSequence().toString();
        }
        
        // TODO: Mimic method signatures of BodyHandlers.ofFile. I.e., no-arg
        //       overload specifies default values and impl. crashes for
        //       "non-sensible" values.
        
        /**
         * Saves the remaining body bytes to a file.<p>
         * 
         * An invocation of this method behaves in exactly the same way as the
         * invocation
         * <pre>
         *     body.{@link #toFile(Path,Set,FileAttribute[])
         *       toFile}(path, opts, new FileAttribute&lt;?&gt;[0]);
         * </pre>
         * where {@code opts} is a {@code Set} containing the options specified
         * to this method.
         * 
         * @param path where to dump the body bytes
         * @param opts specifies how the file is opened
         * 
         * @return the number of bytes written to the file
         *         (capped at {@code Long.MAX_VALUE})
         * 
         * @throws IOException if an I/O error occurs
         */
        long toFile(Path path, OpenOption... opts) throws IOException;
        
        /**
         * Saves the remaining body bytes to a file.<p>
         * 
         * This method is equivalent to
         * <pre>
         *     {@link FileChannel#open(Path,Set,FileAttribute[])
         *       FileChannel.open}(file, opts, attrs);
         * </pre>
         * 
         * ...except if {@code options} is empty, a set of {@code WRITE} and
         * {@code CREATE_NEW} will be used (if the file exists the operation
         * will fail).<p>
         * 
         * Future work will add API support for resuming uploads and downloads.
         * For now, the application will likely want to do something like this:
         * 
         * <pre>
         *   try {
         *       request.body().toFile(path, ...);
         *   } catch (IOException e) {
         *       // Channel read or file write failed
         *       Files.deleteIfExists(path);
         *       throw e;
         *   }
         * </pre>
         * 
         * All exceptions thrown by {@code FileChannel.open()} propagates as-is
         * from this method.
         * 
         * @param path  where to dump the body bytes
         * @param opts  specifies how the file is opened
         * @param attrs an optional list of file attributes to set atomically
         *              when creating the file
         * 
         * @return the number of bytes written to file
         *         (capped at {@code Long.MAX_VALUE})
         * 
         * @throws IOException if an I/O error occurs
         */
        long toFile(
                Path path, Set<? extends OpenOption> opts,
                FileAttribute<?>... attrs)
                throws IOException;
        
        // TODO: toEverythingElse()
        
        /**
         * Iterates all bytes into a byte array.<p>
         * 
         * This method should only be used if the final destination is on Java's
         * heap space. Otherwise, use {@code iterator} and prefer to have the
         * bytebuffer be sent to the final destination as-is. Doing so could
         * enable a faster off-heap direct transfer without moving the bytes
         * through the JVM.
         * 
         * @return all remaining bytes (never {@code null)}
         * 
         * @throws UnsupportedOperationException
         *             if {@code length} is unknown
         * @throws BufferOverflowException
         *             if {@code length} is greater than {@code Integer.MAX_VALUE}
         * @throws MaxRequestBodyConversionSizeExceededException
         *             if the body size exceeds the internal buffer limit
         * @throws IOException
         *             if an I/O error occurs
         */
        byte[] bytes() throws IOException;
    }
}