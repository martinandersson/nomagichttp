package alpha.nomagichttp.message;

import java.net.http.HttpHeaders;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.BiFunction;

// TODO: Document
public interface Request
{
    // TODO: Document
    String method();
    
    /**
     * TODO: Document
     * 
     * Also known as the "path" or "uri". Is the part of the request-line
     * between method and protocol version and includes the full query string.
     * For example, from this request-line:
     * <pre>
     *   GET /hello.txt?something=value HTTP/1.1
     * </pre>
     * 
     * This method will return "/hello.txt?something=value".
     */
    String target();
    
    /**
     * TODO: Document
     * 
     * From:
     * <pre>
     *   GET /hello.txt?something=value HTTP/1.1
     * </pre>
     * 
     * 
     * this method will return "HTTP/1.1".
     */
    String httpVersion();
    
    // TODO: Document
    Optional<String> paramFromPath(String name);
    
    // TODO: Document
    Optional<String> paramFromQuery(String name);
    
    // TODO: Document
    HttpHeaders headers();
    
    // TODO: Document
    Body body();
    
    /**
     * An API for accessing the request body, either as a high-level Java type
     * using conversion methods such as {@link #toText()} or directly consuming
     * bytes by using the low-level {@link #subscribe(Flow.Subscriber)
     * subscribe(Flow.Subscriber&lt;ByteBuffer&gt;)} method.<p>
     * 
     * The application should consume the body, even if this entails
     * <i>discarding</i> it. Not consuming all of the body could have the effect
     * that the server begins parsing a subsequent request head from the same
     * channel at the wrong position.<p>
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
     * If {@code Subscriber.onNext} returns (normally or exceptionally) and the
     * bytebuffer has bytes remaining, then the bytebuffer will be immediately
     * re-published. This makes it possible for a subscriber to cancel his
     * subscription and "hand-off" byte processing to another subscriber
     * (logical framing within the body).<p>
     * 
     * The default implementation does not support multiple subscribers at the
     * same time.<p>
     * 
     * The default implementation assumes that the active subscriber process the
     * bytebuffer synchronously. When {@code onNext} returns (normally or
     * exceptionally) and the bytebuffer has been fully read, then it will be
     * recycled back into a pool of bytebuffers that the server may immediately
     * use for new channel read operations. Processing a published bytebuffer
     * asynchronously in the future has undefined application behavior.<p>
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
    
    // TODO: Currently we don't allow async consumption of the bytebuffers, the
    //       underlying "real" subscriber will autorelease bytebuffers back to
    //       the pool as soon as onNext returns (see LimitedBytePublisher). I
    //       anticipate that our API will accommodate asynchronous destinations
    //       such as AsynchronousFileChannel.write(). Perhaps through a method
    //       such as Request.Body.toFile(). This future implementation will have
    //       to consume bytes asynchronous and so we need to accommodate this in
    //       a smart way, and, we should then also let the user have access to
    //       such async consumption capabilities directly. We could perhaps do
    //       "Request.Body.subscribe(Subscriber<ByteBuffer>)" and
    //       "Request.Body.subscribeAsync(Subscriber<PooledByteBuffer>)". The
    //       latter of which exposes PooledByteBuffer.release(). Or, we can make
    //       PooledByteBufferPublisher public and have Body extend it? Then add
    //       in docs that a manual Subscriber must always release(), or use one
    //       of the conversion methods.
    
    interface Body extends Flow.Publisher<ByteBuffer>
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