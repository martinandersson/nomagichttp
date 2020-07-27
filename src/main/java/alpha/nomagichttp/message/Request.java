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
      * An API for access of the request body.
      * 
      * @author Martin Andersson (webmaster at martinandersson.com)
      */
    interface Body
    {
        /**
         * Returns the body as a string.<p>
         * 
         * The charset used will be taken from the request headers (charset
         * parameter of "Content-Type"). If this information is not present,
         * then UTF-8 will be used.<p>
         * 
         * @return the body as a string
         */
        CompletionStage<String> toText();
        
        // TODO: toEverythingElse()
        
        /**
         * Convert the request body into an arbitrary Java type.<p>
         * 
         * All bytes in the bytebuffers from the network source will be
         * collected into a byte[]. Once all of the body has been read, the
         * byte[] will be passed to the specified function together with a count
         * of valid bytes that can be safely read from the array.<p>
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
        
        /**
         * Returns the request body as a publisher of bytebuffers.<p>
         * 
         * This is a low-level method meant to be used as a fallback if no other
         * API method does the job.<p>
         *
         * In reality, the publisher is actually the network channel. The "body"
         * is just a semantically defined intersection of a flow of bytes from
         * the channel.<p>
         * 
         * The bytes published will be read from the end of the request head
         * until the server has determined the end of the body at which point
         * the subscription is completed. The subscriber is fully responsible
         * for the interpretation of these bytes.<p>
         * 
         * Note that currently, chunked transfer-encoding is not implemented by
         * the default server implementation which is likely to reject chunked
         * messages.<p>
         * 
         * The default server implementation imposes a few restrictions:<p>
         * 
         * 1) Only one subscriber at a time may be active. Once the data has
         * been published, it will never be published again.<p>
         * 
         * 2) As soon as {@code Subscriber.onNext} returns and if the published
         * bytebuffer has no more bytes remaining, it will go back to a pool of
         * bytebuffers and become immediately available as a target for new
         * channel read operations. It is therefore not safe to process the
         * bytebuffer asynchronously.<p>
         * 
         * 3) If {@code onNext} returns and the bytebuffer has bytes remaining,
         * then the bytebuffer will immediately be re-published. Therefore it
         * doesn't really make much sense for the subscriber to <i>not</i> read
         * all of the remaining bytes unless he also cancels the
         * subscription (in which case the remaining bytes will be published to
         * the next subscriber).<p>
         * 
         * 4) Speaking of, the next subscriber may be a server-provided head
         * parser that expects to begin a new HTTP exchange by parsing the next
         * request head. The head parser is guaranteed to fail if it picks up
         * parsing from an arbitrary point in the previous request body. The
         * subscriber should therefor not prematurely cancel it's subscription
         * unless the intention is to immediately re-subscribe with a new
         * subscriber that will consume the rest of the body. A subscriber that
         * is not interested in all of the body should <i>discard</i> bytes
         * until the subscription is completed.<p>
         * 
         * The server will slice the last bytebuffer to make sure the subscriber
         * can not accidentally read past the body limit into a subsequent HTTP
         * message.<p>
         * 
         * 5) Re-subscribing after the request body has been completely read has
         * undefined behavior, most likely, not a good one. So, should not be
         * done.
         * 
         * @return the request body as a publisher of bytebuffers
         */
        // TODO: Remove this method and let Body interface extend Flow.Publisher
        Flow.Publisher<ByteBuffer> asPublisher();
    }
}