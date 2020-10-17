package alpha.nomagichttp.message;

import alpha.nomagichttp.ExceptionHandler;
import alpha.nomagichttp.handler.Handler;

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
 * The {@link Handler request handler} will be invoked as soon as the request
 * head has been fully parsed and to the extent necessarily; interpreted by the
 * server. The contents of the request body will arrive asynchronously and is
 * exposed through the {@link #body() method}.<p>
 * 
 * Methods on this interface that document {@code IllegalStateException} will
 * never throw the exception when accessed by a <i>request handler</i>. However,
 * the exception may be thrown when accessed by an <i>{@link ExceptionHandler
 * exception handler}</i> as these handlers may be called earlier in time before
 * all of the parts of the request object has been bound.<p>
 * 
 * The implementation is thread-safe.<p>
 * 
 * The request handler is not required to consume the request or its body. If
 * there is a body present and it is not consumed then it will be silently
 * discarded as late in the HTTP exchange process as possible, which is when the
 * server's {@link Response#body() response body} subscription completes.<p> 
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
     * 
     * @return the parameter value
     * 
     * @throws NullPointerException
     *           if {@code name} is {@code null}
     * 
     * @throws IllegalStateException
     *           if path parameters has not yet been bound (see {@link Request})
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
     * Returns the request body.<p>
     * 
     * @return the request body
     * 
     * @throws IllegalStateException
     *           if body has not yet been bound (see {@link Request})
     * 
     * @see Body
     */
    Body body();
    
    /**
     * An API for accessing the request body, either as a high-level Java type
     * using conversion methods such as {@link #toText()} or directly consuming
     * the message bytes by using the low-level {@link
     * #subscribe(Flow.Subscriber)
     * subscribe(Flow.Subscriber&lt;PooledByteBufferHolder&gt;)} method.<p>
     * 
     * The bytes are not saved for future re-use and an attempt to subscribe to
     * the bytes more than once will signal an {@code IllegalStateException} to
     * the subscriber. This is also true even if a new subscription is
     * immediately cancelled without consuming any bytes.<p>
     * 
     * The implementation is thread-safe. In particular - and unlike the
     * unbelievably restrictive rule §3.1 in an otherwise very "async-oriented"
     * <a href="https://github.com/reactive-streams/reactive-streams-jvm/tree/d4d08aadf7d6b4345b2e59f756adeaafd998fec7#3-subscription-code">Reactive Streams</a>
     * specification - methods on the {@link Flow.Subscription} instance
     * given to the subscriber from the server when subscribing to the request
     * body, may be called by any thread at any time.<p>
     * 
     * 
     * <h3>Subscribing to bytes with a {@code Flow.Subscriber}</h3>
     * 
     * The subscriber will receive bytebuffers in the same order they are read
     * from the underlying channel (the subscriber can not read passed the body
     * boundary because the server will complete the subscription and possibly
     * limit the last bytebuffer).<p>
     * 
     * The subscriber may request/demand any number of bytebuffers and even
     * process them asynchronously, but only when the bytebuffer has been {@link
     * PooledByteBufferHolder#release() released} will the next bytebuffer be
     * published.<p>
     * 
     * Releasing the bytebuffer with bytes remaining to be read will cause the
     * bytebuffer to immediately be re-published.<p>
     * 
     * Cancelling the subscription does not cause the bytebuffer to be released.
     * Releasing has to be done explicitly, or implicitly through an exceptional
     * return of {@code Subscriber.onNext()}. An exceptional return from onNext
     * will also void the subscription which just like {@code
     * Subscription.cancel} [perhaps eventually] stops the publisher from
     * publishing more items.<p>
     * 
     * The HTTP exchange is considered done as soon as both the server's
     * response body subscription <i>and</i> the application's request body
     * subscription have both completed. Not until then will the next HTTP
     * message-exchange commence. This means that a request body subscriber must
     * ensure his subscription runs all the way to the end or is cancelled.
     * Subscribing to the body but never complete the subscription will lead to
     * a halt in progress for the underlying channel.<p>
     * 
     * However, if the server's response body subscription completes and no
     * request body subscriber arrived, then the server will assume the body was
     * intentionally ignored and proceed to discard it, after which it can not
     * be subscribed to by the application any more.<p>
     * 
     * If a response must be sent back immediately but processing the
     * request body bytes must be delayed, then there's at least two ways of
     * solving this. Either delay completing the server's response body
     * subscription or register a request body subscriber but delay requesting
     * items.<p>
     * 
     * Finally, as a word of caution; the default implementation uses
     * <i>direct</i> bytebuffers in order to support "zero-copy" transfers.
     * I.e., no data is moved into Java heap space unless the subscriber itself
     * causes this to happen. Whenever possible, always pass forward the
     * bytebuffers to the destination without reading the bytes in application
     * code.<p>
     * 
     * The implementation is thread-safe.
     * 
     * 
     * @author Martin Andersson (webmaster at martinandersson.com)
     */
    // TODO: Any heap based read of bytes must save bytes and return same subsequently
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
        
        // TODO: Mimic method signatures of BodyHandlers.ofFile. I.e., no-arg
        //       overload specifies default values and impl. crashes for
        //       "non-sensible" values.
        
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