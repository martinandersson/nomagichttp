package alpha.nomagichttp.message;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.action.BeforeAction;
import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.util.IllegalLockUpgradeException;
import alpha.nomagichttp.util.JvmPathLock;
import alpha.nomagichttp.util.ScopedValues;

import java.io.IOException;
import java.nio.Buffer;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.RandomAccess;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * An inbound HTTP request.<p>
 * 
 * The invoked entity receiving a request object may have been associated with a
 * unique path pattern, containing path parameters, which are available in the
 * {@link Request#target()}.<p>
 * 
 * For example, although request "/hello" matches before-action "/:foo" and
 * {@link Route} "/:bar", the former will have to use the key "foo" when
 * retrieving the segment value from the target and a request handler of the
 * latter will have to use the key "bar".<p>
 * 
 * The keys may be the same, but the values may differ. Suppose a before-action
 * is registered using the pattern "/*seg" and a route is registered using
 * "/hello/:seg". For an inbound request "/hello/world", the former's "seg"
 * parameter will map to the value "/hello/world" but the route's request
 * handler will observe the value "world" using the same key.<p>
 * 
 * To support path parameters, the request object will be a unique instance per
 * receiving entity. A {@link BeforeAction} will receive a different instance
 * than what the {@link RequestHandler} receives. Logically, both request
 * objects represent the same request, and they share almost all the same
 * underlying components, except for the target component.<p>
 * 
 * All other components of the request object are shared by all instances
 * created throughout the HTTP exchange; most importantly, the request
 * attributes and body. Changes to these structures are visible across execution
 * boundaries, such as setting attributes and consuming the body bytes (which
 * should be done only once).<p>
 * 
 * The implementation and containing components are mostly thread-safe; the
 * exception being {@code Request.Body} and the {@link #trailers() trailers}
 * method.<p>
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
     * also suggests that the server move trailers to the "existing header
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
     * This method performs channel read operations only the first time it is
     * accessed. The resulting trailers are cached and returned on subsequent
     * invocations.
     * 
     * @return trailing headers
     * 
     * @throws IllegalStateException
     *             if the body has not been consumed
     * @throws HeaderParseException
     *             if parsing fails
     * @throws IllegalStateException
     *             if a previous attempt at parsing trailers failed
     * @throws MaxRequestTrailersSizeException
     *             if the length of trailers exceeds the configured tolerance
     * @throws IOException
     *             if an I/O error occurs
     */
    BetterHeaders trailers() throws IOException;
    
    /**
     * The resource-target of a request.<p>
     * 
     * The components of the target are path segments, path parameters (named
     * segments), query key/value pairs, and a fragment (document anchor).<p>
     * 
     * Path parameters come in two forms; single- and catch-all. Single path
     * parameters are required for the action/handler to be matched. Catch-all
     * path parameters are optional, but the server will make sure the value is
     * always present and starts with a '/'. Query parameters are optional, and
     * so may not be present. Read more in the JavaDoc of {@link Route}.
     * 
     * <h2>Features of the server's parser of the query string</h2>
     * 
     * The separator for a query's key from its value is the equals sign
     * character ('='). The value ends with an ampersand ('&amp;') — which marks
     * the start of a new key/value pair — a whitespace character or the number
     * sign ('&#35;'). For example:
     * 
     * <pre>
     *   /where?key1=value1&amp;key2=value2
     * </pre>
     * 
     * The semicolon ('&#59;') has no special meaning; it will <i>not</i>
     * deliminate a new key/value pair, and so it will become part of the query
     * key or value, depending on its location. Contrary to
     * <a href="https://www.w3.org/TR/1999/REC-html401-19991224/appendix/notes.html#h-B.2.2">W3</a>,
     * we argue that kicking a can of problems down the road and rely on some
     * unspecified magic sauce being present on the server, is ill-advised and
     * unprofessional.<p>
     * 
     * The parser supports repeated query keys and keys with no value (will be
     * mapped to the empty string).<p>
     * 
     * Tokens (path parameter values, query keys/values) are not interpreted or
     * parsed. In particular, there is no API-support for so called <i>path
     * matrix variables</i> (nor is this magic standardized) and appending
     * brackets ("[]") to the query key has no special meaning; they will become
     * part of the query key itself.<p>
     * 
     * If embedding multiple query values into one key entry is desired, then
     * splitting and parsing the value with whatever delimiting character one
     * chooses is pretty straight forward:
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
     * Instead of using a non-standardized separator, it's more straight forward
     * and fool-proof (number separator would be dependent on regional format)
     * to rely on repetition instead:
     * 
     * <pre>{@code
     *     // "?number=1&number=2&number=3"
     *     int[] ints = request.target()
     *                         .queryStream("number")
     *                         .mapToInt(Integer::parseInt)
     *                         .toArray();
     * }</pre>
     * 
     * <h2>Decoded versus raw</h2>
     * 
     * Methods which do not carry the "raw" suffix will percent-decode (aka. URL
     * decode) the tokens (segment values, query keys, path- and query parameter
     * values). For example, "Hello%20World" becomes "Hello World".<p>
     * 
     * The raw version is useful when there is a need to unescape values using
     * a different algorithm. For example, a browser submitting an HTML form
     * will by default use the <a href="https://www.w3.org/TR/html401/interact/forms.html#h-17.13.4.1">
     * application/x-www-form-urlencoded</a> encoding, which escapes space
     * characters using the plus character ('+') (and not "%20" as used by
     * percent-encoding).
     * 
     * <pre>{@code
     *     import static java.net.URLDecoder.decode;
     *     import static java.nio.charset.StandardCharsets.UTF_8;
     *     ...
     *     String nondecoded = request.target().queryFirstRaw("my-input-name");
     *     String formdata = decode(nondecoded, UTF_8);
     * }</pre>
     * 
     * TODO: Discourage base64url encoding for text-to-text.
     * 
     * <h2>Thread safety and identity</h2>
     * 
     * The implementation is thread-safe and non-blocking.<p>
     * 
     * The implementation is not required to implement {@code hashCode} and
     * {@code equals}.
     * 
     * @author Martin Andersson (webmaster at martinandersson.com)
     * 
     * @see <a href="https://en.wikipedia.org/wiki/Query_string">"Query-string", Wikipedia</a>
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc3986#section-2.1">"2.1. Percent-Encoding", RFC 3986</a>
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
     * Is an API for reading the request body in various forms.<p>
     * 
     * A few examples:
     * <pre>{@code
     *   // Convert all bytes to a String
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
     * <h2>Handling errors</h2>
     * 
     * In general, high-level exception types — in particular, when documented —
     * occurs before a channel read operation is made and will leave the
     * channel's input stream open. The application can generally attempt a new
     * operation to recover the body (for example, {@code toText() >} {@code
     * CharacterCodingException}).<p>
     * 
     * Errors that originate from the channel's read operation will have shut
     * down the input stream. Before attempting to read the body again, always
     * check first if
     * <pre>
     *   {@link ScopedValues#channel() channel
     *       }().{@link ClientChannel#isInputOpen() isInputOpen}()
     * </pre>
     * 
     * <h2>Saving to file</h2>
     * 
     * All implementations of the {@code toFile} and {@code toFileNoLock}
     * methods use the standard library's
     * {@link FileChannel#open(Path,Set,FileAttribute[])}, and passes through
     * almost all arguments that are named the same, as-is. The one exception is
     * the set of {@link OpenOption}s, but only if the set is empty, in which
     * case a set of {@link StandardOpenOption#WRITE} and
     * {@link StandardOpenOption#CREATE_NEW} will be used. Code that specifies
     * any open option(s) ought to re-specify {@code WRITE}!.<p>
     * 
     * All exceptions from
     * {@link FileChannel#open(Path,Set,FileAttribute[]) FileChannel.open}
     * propagates as-is, and have — for the sake of brevity — not been repeated
     * by the JavaDoc in this interface. For example, trying to save the body to
     * a file that already exists, and no open option was specified, may throw
     * a {@link FileAlreadyExistsException} <i>(
     * {@linkplain java.nio.file optional specific exception})</i>.<p>
     * 
     * The {@code toFile} methods will try to acquire a write-lock which will
     * ensure that no other co-operating thread within the currently running JVM
     * can read from, or write to the same file (returning exceptionally if a
     * lock can not be acquired). With being <i>co-operative</i> means that any
     * other concurrent thread must acquire a lock for the same file before
     * accessing it, using {@link JvmPathLock} directly, or by calling a method
     * that uses the {@code JvmPathLock}, which is exactly what the
     * {@code toFile} methods do.<p>
     * 
     * The {@code toFileNoLock} methods will <strong>not</strong> acquire any
     * kind of lock; not in the currently running JVM nor outside of it. The
     * application has the responsibility to co-ordinate file access; if
     * warranted.<p>
     * 
     * Future work will add API-support for resuming file uploads and downloads.
     * For now, an application that does not want to implement such a feature on
     * its own, will likely want to do something like this:
     * 
     * <pre>
     *   try {
     *       request.body().toFile(path, ...);
     *   } catch (IOException rethrow) {
     *       // Channel read or file write failed
     *       Files.deleteIfExists(path);
     *       throw rethrow;
     *   }
     * </pre>
     * 
     * <h2>Consuming bytes</h2>
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
         *             if {@link #length() length} is unknown
         * @throws BufferOverflowException
         *             if {@link #length() length} is greater than
         *             {@code Integer.MAX_VALUE}
         * @throws CharacterCodingException
         *             if input is malformed, or
         *             if a character is unmappable
         * @throws MaxRequestBodyBufferSizeException
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
         *             if {@link #length() length} is unknown
         * @throws BufferOverflowException
         *             if {@link #length() length} is greater than
         *             {@code Integer.MAX_VALUE}
         * @throws CharacterCodingException
         *             if input is malformed, or
         *             if a character is unmappable
         * @throws MaxRequestBodyBufferSizeException
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
         *     body.{@link #toFile(Path,long,TimeUnit,Set,FileAttribute[])
         *       toFile}(path, timeout, unit, opts, new FileAttribute&lt;?&gt;[0]);
         * </pre>
         * where the values used for {@code timeout} and {@code unit} are
         * derived from
         * <pre>
         *     {@link ScopedValues#httpServer() httpServer
         *     }().{@link HttpServer#getConfig() getConfig
         *     }().{@link Config#timeoutFileLock() timeoutFileLock}()
         * </pre>
         * and {@code opts} is a {@code Set} containing the options specified to
         * this method.
         * 
         * @param path where to dump the body bytes
         * @param opts specifies how the file is opened
         * 
         * @return the number of bytes written to the file
         *         (capped at {@link Long#MAX_VALUE})
         * 
         * @throws NullPointerException
         *             if any argument is {@code null}
         * @throws NoSuchElementException
         *             if the server instance is not bound
         * @throws IllegalLockUpgradeException
         *             if the current thread holds a read-lock for the same path
         * @throws InterruptedException
         *             if the current thread is interrupted while acquiring a lock
         * @throws TimeoutException
         *             if a lock is not acquired within the specified duration
         * @throws IOException
         *             if an I/O error occurs
         */
        long toFile(Path path, OpenOption... opts)
                throws InterruptedException, TimeoutException, IOException;
        
        /**
         * Saves the remaining body bytes to a file.<p>
         * 
         * This method is equivalent to
         * <pre>
         *     {@link FileChannel#open(Path,Set,FileAttribute[])
         *       FileChannel.open}(file, opts, attrs);
         * </pre>
         * except if {@code opts} is empty, a set of
         * {@link StandardOpenOption#WRITE WRITE} and
         * {@link StandardOpenOption#CREATE_NEW CREATE_NEW} will be used.<p>
         * 
         * See {@link Request.Body} for more details.
         * 
         * @param path     where to dump the body bytes
         * @param timeout  the time to wait for a lock
         * @param unit     the time unit of the timeout argument
         * @param opts     specifies how the file is opened
         * @param attrs    attributes to set atomically when creating the file
         * 
         * @return the number of bytes written to file
         *         (capped at {@link Long#MAX_VALUE})
         * 
         * @throws NullPointerException
         *             if any argument is {@code null}
         * @throws IllegalLockUpgradeException
         *             if the current thread holds a read-lock for the same path
         * @throws InterruptedException
         *             if the current thread is interrupted while acquiring a lock
         * @throws TimeoutException
         *             if a lock is not acquired within the specified duration
         * @throws IOException
         *             if an I/O error occurs
         */
        long toFile(
                Path path, long timeout, TimeUnit unit,
                Set<? extends OpenOption> opts, FileAttribute<?>... attrs)
                throws InterruptedException, TimeoutException, IOException;
        
        /**
         * Saves the remaining body bytes to a file.<p>
         * 
         * An invocation of this method behaves in exactly the same way as the
         * invocation
         * <pre>
         *     body.{@link #toFileNoLock(Path,Set,FileAttribute[])
         *       toFileNoLock}(path, opts, new FileAttribute&lt;?&gt;[0]);
         * </pre>
         * where {@code opts} is a {@code Set} containing the options specified
         * to this method.
         * 
         * @param path where to dump the body bytes
         * @param opts specifies how the file is opened
         * 
         * @return the number of bytes written to the file
         *         (capped at {@link Long#MAX_VALUE})
         * 
         * @throws NullPointerException
         *             if any argument is {@code null}
         * @throws IOException
         *             if an I/O error occurs
         */
        long toFileNoLock(Path path, OpenOption... opts) throws IOException;
        
        /**
         * Saves the remaining body bytes to a file.<p>
         * 
         * This method is equivalent to
         * <pre>
         *     {@link FileChannel#open(Path,Set,FileAttribute[])
         *       FileChannel.open}(path, opts, attrs);
         * </pre>
         * except if {@code opts} is empty, a set of
         * {@link StandardOpenOption#WRITE WRITE} and
         * {@link StandardOpenOption#CREATE_NEW CREATE_NEW} will be used.<p>
         * 
         * See {@link Request.Body} for more details.
         * 
         * @param path     where to dump the body bytes
         * @param opts     specifies how the file is opened
         * @param attrs    attributes to set atomically when creating the file
         * 
         * @return the number of bytes written to file
         *         (capped at {@link Long#MAX_VALUE})
         * 
         * @throws NullPointerException
         *             if any argument is {@code null}
         * @throws IOException
         *             if an I/O error occurs
         */
        long toFileNoLock(
                Path path, Set<? extends OpenOption> opts,
                FileAttribute<?>... attrs) throws IOException;
        
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
         * @throws MaxRequestBodyBufferSizeException
         *             if the body size exceeds the internal buffer limit
         * @throws IOException
         *             if an I/O error occurs
         */
        byte[] bytes() throws IOException;
    }
}