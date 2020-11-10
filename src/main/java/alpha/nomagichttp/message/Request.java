package alpha.nomagichttp.message;

import alpha.nomagichttp.ExceptionHandler;

import java.net.http.HttpHeaders;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.NetworkChannel;
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
     * @return the request-line method token (never {@code null} or empty)
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
     * Returns a parameter value, or an empty optional if the value is not
     * bound.<p>
     * 
     * Suppose, for example, that the server has a route with a parameter "id"
     * declared:<p>
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
     * an empty Optional.
     * 
     * @param name of parameter
     * 
     * @return the parameter value
     * 
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
     * Returns the request body.<p>
     * 
     * @return the request body
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
     * Is an API for accessing the request body in various forms.<p>
     * 
     * Utility methods (for example, {@link #toText()}), returns a {@link
     * CompletionStage} because the request handler will be invoked and
     * therefore have access to the Body API immediately after the server is
     * done parsing the request head. At this point in time, not all bytes will
     * necessarily have made it through on the network.<p>
     * 
     * The body bytes may be converted into any arbitrary Java type using the
     * {@link #convert(BiFunction)} method or consumed directly "on arrival"
     * using the {@link #subscribe(Flow.Subscriber)} method.<p>
     * 
     * The implementation is thread-safe. In particular - and unlike the
     * unbelievably restrictive rule §3.1 in an otherwise very "async-oriented"
     * <a href="https://github.com/reactive-streams/reactive-streams-jvm/tree/d4d08aadf7d6b4345b2e59f756adeaafd998fec7#3-subscription-code">Reactive Streams</a>
     * specification - methods on the {@link Flow.Subscription} instance
     * given to the body subscriber may be called by any thread at any time.<p>
     * 
     * Some utility methods such as {@code toText()} may save the returned stage
     * for future re-use, for example an {@link ExceptionHandler exception
     * handler} may also be interested in accessing the request body.<p>
     * 
     * However, the body bytes can not be directly consumed more than once; they
     * are not saved by the server. An attempt to {@code convert(...)} or {@code
     * subscribe(...)} more than once will result in an {@code
     * IllegalStateException}.<p>
     * 
     * Same is is also true for utility methods that "trickle down". If for
     * example method {@code convert(...)} is used followed by {@code toText()},
     * then the latter will complete exceptionally with an {@code
     * IllegalStateException}.<p>
     * 
     * And same is also true even if a {@code Flow.Subscription} is immediately
     * cancelled with or without actually consuming any bytes (application is
     * assumed to ignore the body, followed by a server-side discard of it).<p>
     * 
     * The normal way to reject an operation would be to blow up the calling
     * thread, even for asynchronous operations. For example, {@code
     * ExecutorService.submit()} throws {@code RejectedExceptionException} and
     * {@code AsynchronousByteChannel.read()} throws {@code
     * IllegalArgumentException} - just to name a few.<p>
     * 
     * For good or bad, the Reactive Streams specification mandates that all
     * exceptions are signalled through the subscriber. In order then to have a
     * coherent API, all exceptions produced by the Body API will be delivered
     * through the result carrier ({@code CompletionStage} and {@code
     * Flow.Subscriber}). This also has the implication that exceptions are not
     * documented using the standard {@code @throws} tag but rather inline with
     * the rest of the text. The only exception to this rule is {@code
     * NullPointerException} which will blow up the calling thread wherever
     * warranted.<p>
     * 
     * In general, high-level exception types - in particular, when documented -
     * does not close the underlying channel and so the application can chose to
     * recover from them. The reverse is true for unexpected errors, in
     * particular, errors that originates from the underlying channel. The
     * safest bet for an application when attempting error recovery is to always
     * check first if the channel is still open ({@code
     * Request.channel().isOpen()}).<p>
     * 
     * 
     * <h3>Subscribing to bytes with a {@code Flow.Subscriber}</h3>
     * 
     * The subscriber will receive bytebuffers in the same order they are read
     * from the underlying channel. The subscriber can not read passed the
     * message/body boundary because the server will complete the subscription
     * before then and if need be, limit the last bytebuffer.<p>
     * 
     * <h4>Releasing</h4>
     * 
     * The published bytebuffers are pooled and may be processed synchronously
     * or asynchronously. Whenever the application has finished processing
     * a bytebuffer, it must be {@link PooledByteBufferHolder#release()
     * released} which is a signal to the server that the bytebuffer may be
     * re-used for new channel operations. The thread releasing may be used to
     * immediately publish new bytebuffers to the subscriber or initiate new
     * asynchronous operations on the underlying channel.<p>
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
     * The subscriber may request/demand any number of bytebuffers. In general
     * though, the easiest and safest approach is most likely to simply request
     * and process one bytebuffer at a time.<p>
     * 
     * It is possible for the application to request and also receive new
     * bytebuffers before the previously received bytebuffers have been
     * released. However, awaiting a certain number of bytebuffers before
     * processing them is strongly discouraged and may even have dire
     * consequences. There is no support in the Body API for the application to
     * know if a future publication of a bytebuffer is immediately available nor
     * can the application know exactly how many bytebuffers still remains in
     * the server's pool. Or put in other words, this practice could end up
     * imposing unnecessary delays or even starve the server's pool of
     * bytebuffers to use for new channel operations, causing a complete halt in
     * progress for the underlying channel.<p>
     * 
     * The recommended approach is to request and process one bytebuffer at a
     * time. This may also entail a re-designing of what would otherwise have
     * been a synchronous approach to become an asynchronous approach instead.
     * For example, instead of using a {@code GatheringByteChannel} which
     * expects a {@code ByteBuffer[]}, use an {@code AsynchronousByteChannel}
     * which expects a single {@code ByteBuffer} and when the operation's
     * completion handler is called, release the bytebuffer and request a new
     * bytebuffer from the subscription. Please also note that blocking the
     * request thread is never a good idea to begin with and hurts
     * scalability!<p>
     * 
     * Unfortunately, the Reactive Stream specification calls this approach an
     * "inherently inefficient 'stop-and-wait' protocol". Well, this is plain
     * wrong. The bytebuffers are pooled and cached upstream already. Requesting
     * a bytebuffer is essentially the same as polling a stupidly fast queue of
     * available bytebuffers and there simply does not exist - surprise surprise
     * - a good reason to engage in "premature optimization".<p>
     * 
     * Speaking of optimization; the default implementation uses <i>direct</i>
     * bytebuffers in order to support "zero-copy" transfers. I.e., no data is
     * moved into Java heap space unless the subscriber itself causes this to
     * happen. Whenever possible, always pass forward the bytebuffers to the
     * destination without reading the bytes in application code!<p>
     * 
     * <h4>Thread Semantics</h4>
     * 
     * All signals to the subscriber are executed serially with a happens-before
     * relationship. Assuming the class data is not accessed by foreign threads
     * outside of the delivery of these signals; no special care needs to be put
     * in place such as volatile- and/or atomic fields.<p>
     * 
     * The effect of {@code Subscription.request()} - if called by the thread
     * delivering a signal to the subscriber - will not be immediate. Instead
     * the requested demand/value will be scheduled and then the method returns.
     * I.e., {@code Subscriber.onSubscribe()} and {@code Subscriber.onNext()}
     * will always complete without reentrancy and recursion.<p>
     * 
     * However, an asynchronous call to {@code request()} may prompt the same
     * thread to immediately get busy delivering the next subscriber signal.<p>
     * 
     * {@code Subscription.cancel()} will only have an immediate effect if
     * called by the thread running the subscriber. If called asynchronously,
     * the effect may be eventual and the subscriber may observe a bytebuffer
     * delivery even after the cancel method returns (at most one "extra"
     * delivery).<p>
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
     * Exceptions thrown by the {@code Flow.Subscriber.onSubscribe()} method
     * propagates to the calling thread, i.e., the one calling {@code
     * Request.Body.subscribe()}. If this thread is the request thread, then
     * standard {@link ExceptionHandler exception handling} is kicked off.<p>
     * 
     * Exceptions thrown by the subscriber's {@code onNext()} and {@code
     * onComplete()} methods will cause the server to log the error and perform
     * the channel-close procedure documented in {@link
     * Response#mustCloseAfterWrite()}. This will also void the underlying
     * subscription and even though {@code onError()} is meant to be a vehicle
     * for <i>publisher</i> errors, the server will still gracefully complete
     * the subscription by signalling a {@link ClosedPublisherException}.<p>
     * 
     * Exceptions signalled to {@code Subscriber.onError()} indicates low-level
     * problems with the underlying channel, meaning it's quite futile for the
     * application to try to recover from them. The error will already have been
     * logged by the server who also performed the channel-close procedure
     * documented in {@code Response.mustCloseAfterWrite()}.<p>
     * 
     * Exceptions from {@code Subscriber.onError()} itself will be logged but
     * otherwise ignored.<p>
     * 
     * Even if the server has a reaction for some observed exceptions - such as
     * closing the channel as previously noted - this doesn't stop the exception
     * from being propagated. For example, if the application asynchronously
     * calls {@code Subscription.request()} from a new thread, a call which
     * might immediately and synchronously trigger the publication of a new item
     * delivery to {@code Subscriber.onNext()}, and if this method in turn
     * returns exceptionally which prompts the server to intercept and close the
     * channel, then the application code will still return exceptionally from
     * it's call to the top-level {@code Subscription.request()} method
     * observing the same exception instance.
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