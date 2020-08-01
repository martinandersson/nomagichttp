package alpha.nomagichttp.message;

import java.net.http.HttpHeaders;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.function.BiFunction;

/**
 * An inbound HTTP request.<p>
 * 
 * The handler will be invoked as soon as the request head has been fully
 * parsed and to the extent necessarily; interpreted by the server. The request
 * body will arrive asynchronously which the handler can access using the
 * {@link #body() method}.<p>
 * 
 * TODO: Once we have auto-discard, make note the handler can write a response
 * immediately without consuming the body.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see <a href="https://tools.ietf.org/html/rfc7230#section-3.1.1">RFC 7230 §3.1.1</a>
 * @see <a href="https://tools.ietf.org/html/rfc7230#section-3.3">RFC 7230 §3.3</a>
 */
public interface Request
{
    /**
     * Returns the request-line's method token, such as "GET" or "POST".<p>
     * 
     * In the following example, the method is "GET":
     * <pre>{@code
     *   GET /hello.txt HTTP/1.1
     *   User-Agent: curl/7.16.3 libcurl/7.16.3 OpenSSL/0.9.7l zlib/1.2.3
     *   Host: www.example.com
     *   Accept: text/plain;charset=utf-8
     * }</pre>
     * 
     * @return the request-line method token
     */
    String method();
    
    /**
     * Returns the request-line's resource-target.<p>
     *
     * In the following example, the resource-target is
     * "/hello.txt?query=value":
     * <pre>{@code
     *   GET /hello.txt?query=value HTTP/1.1
     *   User-Agent: curl/7.16.3 libcurl/7.16.3 OpenSSL/0.9.7l zlib/1.2.3
     *   Host: www.example.com
     *   Accept: text/plain;charset=utf-8
     * }</pre>
     *
     * @return the request-line's resource-target
     */
    String target();
    
    /**
     * Returns the request-line's HTTP version.<p>
     *
     * In the following example, the HTTP version is "HTTP/1.1":
     * <pre>{@code
     *   GET /hello.txt HTTP/1.1
     *   User-Agent: curl/7.16.3 libcurl/7.16.3 OpenSSL/0.9.7l zlib/1.2.3
     *   Host: www.example.com
     *   Accept: text/plain;charset=utf-8
     * }</pre>
     *
     * @return the request-line's HTTP version
     */
    String httpVersion();
    
    /**
     * Returns a parameter value, or an empty optional if value is not bound.<p>
     * 
     * Suppose the server has a route with a parameter "id" declared:<p>
     * <pre>
     *   /account/{id}
     * </pre>
     * 
     * For the following request, {@code paramFromPath("id")} would return
     * "123":
     * <pre>
     *   GET /account/123 HTTP/1.1
     *   ...
     * </pre>
     * 
     * A resource-target "/account" without the parameter value would still
     * match the route and call the handler, but this method would then return
     * an empty optional.
     * 
     * @param name of parameter
     * @return the parameter value
     * @throws NullPointerException if {@code name} is {@code null}
     */
    Optional<String> paramFromPath(String name);
    
    /**
     * Throws UnsupportedOperationException (query parameters are not yet
     * implemented).
     */
    Optional<String> paramFromQuery(String name);
    
    /**
     * Returns the HTTP headers.
     * 
     * @return the HTTP headers
     * 
     * @see HttpHeaders
     */
    HttpHeaders headers();
    
    /**
     * Returns the request body.
     * 
     * @return the request body
     * 
     * @see Body
     */
    Body body();
    
    /**
     * An API for accessing the request body, either as a high-level Java type
     * using conversion methods such as {@link #toText()} or directly consuming
     * bytes by using the low-level {@link #subscribe(Flow.Subscriber)
     * subscribe(Flow.Subscriber&lt;PooledByteBufferHolder&gt;)} method.<p>
     * 
     * The application should consume the body, even if this entails
     * <i>discarding</i> it. Not consuming all of the body will have the effect
     * that the server crashes when attempting to subsequently subscribe a
     * request head parser.<p>
     * 
     * An attempt to consume the body more than once should not be done and has
     * undefined application behavior. The default implementation does not save
     * the bytes once they have been consumed.<p>
     * 
     * 
     * <h3>Subscribing to bytes with a {@code Flow.Subscriber}</h3>
     *
     * The subscription will be completed as soon as the server has determined
     * that the end of the body has been reached.<p>
     *
     * If need be, the server will slice the last published bytebuffer to make
     * sure that the subscriber can not accidentally read past the body limit
     * into a subsequent HTTP message from the same channel. I.e., it is safe
     * for the subscriber to read <i>all remaining bytes</i> of each published
     * bytebuffer.<p>
     * 
     * Only one subscriber at a time is allowed. This subscriber will receive
     * bytebuffers orderly as they are read from the underlying channel. The
     * subscriber may process the bytebuffers asynchronously, but only when the
     * bytebuffer has been {@link PooledByteBufferHolder#release() released}
     * will the next bytebuffer be published.<p>
     * 
     * Releasing the bytebuffer with bytes remaining to be read will cause the
     * bytebuffer to be immediately re-published. This makes it possible for a
     * subscriber to cancel his subscription and "hand-off" byte processing to
     * another subscriber (logical framing within the body).<p>
     * 
     * Cancelling the subscription does not cause the bytebuffer to be released.
     * Releasing has to be done explicitly, or implicitly through an exceptional
     * return of {@code Subscriber.onNext()}.<p>
     * 
     * Finally, as a word of caution; the default implementation uses
     * <i>direct</i> bytebuffers in order to support "zero-copy" transfers.
     * I.e., no data is moved into Java heap space unless the subscriber itself
     * causes this to happen. Whenever possible, always pass forward the
     * bytebuffers to the destination without reading the bytes in application
     * code.
     * 
     * 
     * @author Martin Andersson (webmaster at martinandersson.com)
     */
    
    // TODO: See beginning of javadoc. We ask the handler to consume the body or
    //       discard it - otherwise dire consequences. A) This is not enforced,
    //       so yes, dire consequences can happen - not good! Some type of
    //       fail-fast from the server would be great. B) This probably also
    //       translates to a lot of boilerplate code for some handlers; forced
    //       to "make a decision" and write code for a body they possibly wasn't
    //       even interested in to start with. I propose that the server
    //       auto-discards bodies that wasn't consumed, including partial
    //       bodies - and no logging about it (really up to the handler what he
    //       makes out of the body or the absence of such). In fact, probably
    //       even required behavior, to be applied after error handling. I mean,
    //       what if the handler crashes half-way through consuming the body,
    //       then an error handler "rescues" the situation by responding an
    //       error code. The application would rightfully think that future HTTP
    //       exchanges can take place as if nothing ever happened.
    //       When should auto-discard commence? Probably something like as soon
    //       as the response completes, that's about as much as we can wait. At
    //       this point the server wants to restart a new exchange and he should
    //       be able to assume that the handler was never interested in the body.
    
    interface Body extends Flow.Publisher<PooledByteBufferHolder>
    {
        /**
         * Returns the body as a string.<p>
         * 
         * The charset used for decoding will be taken from the request headers
         * ("Content-Type" media type parameter "charset" only if the type is
         * "text"). If this information is not present, then UTF-8 will be
         * used.<p>
         * 
         * @return the body as a string
         */
        CompletionStage<String> toText();
        
        /**
         * Save the request body to a file.<p>
         * 
         * This method is equivalent to {@link
         * AsynchronousFileChannel#open(Path, OpenOption...)} except
         * if {@code options} is empty, a set of {@code WRITE}, {@code CREATE}
         * and {@code TRUNCATE_EXISTING} will be used. I.e, by default, a new
         * file will be created or an existing file will be overwritten.<p>
         * 
         * {@code IOException}s thrown by the call to open a file channel is
         * delivered through the returned {@code CompletionStage}.<p>
         * 
         * Option {@code READ} should not be specified.
         * 
         * @param file to dump body into
         * @param options specifying how the file is opened
         * 
         * @return the number of bytes written to file
         */
        CompletionStage<Long> toFile(Path file, OpenOption... options);
    
        /**
         * Save the request body to a file.<p>
         * 
         * This method is equivalent to {@link
         * AsynchronousFileChannel#open(Path, Set, ExecutorService, FileAttribute[])}
         * except if {@code options} is empty, a set of {@code WRITE}, {@code
         * CREATE} and {@code TRUNCATE_EXISTING} will be used. I.e, by default,
         * a new file will be created or an existing file will be
         * overwritten.<p>
         * 
         * {@code IOException}s thrown by the call to open a file channel is
         * delivered through the returned {@code CompletionStage}.<p>
         * 
         * Option {@code READ} should not be specified.
         * 
         * @param file     to dump body into
         * @param options  specifying how the file is opened
         * @param attrs    an optional list of file attributes to set atomically
         *                 when creating the file
         * 
         * @return the number of bytes written to file
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