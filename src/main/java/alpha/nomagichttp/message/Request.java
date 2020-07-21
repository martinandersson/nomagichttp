package alpha.nomagichttp.message;

import java.net.http.HttpHeaders;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

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
    String version();
    
    // TODO: Document
    Optional<String> paramFromPath(String name);
    
    // TODO: Document
    Optional<String> paramFromQuery(String name);
    
    // TODO: Document
    HttpHeaders headers();
    
    // TODO: Document
    Body body();
    
     /**
      * TODO: Document
      *
      * Notes:
      *
      * The implementation returned by this method allows only one subscriber to
      * be active at any moment in time.
      *
      * It is assumed that as soon as Subscriber.onNext() returns, processing of
      * the bytebuffer has completed and the publisher is free to recycle the
      * buffer in an internally used pool for future channel read operations and
      * subscriber deliveries.
      *
      * The implementation will complete the subscription once it has been
      * determined the end of the body has been reached.
      * 
      * It is not safe to re-subscribe. This would infer with the server's future
      * byte processing of the next request head.
      */
    interface Body {
         // TODO: Document
        CompletionStage<String> toText();
        
        // TODO: toEverythingElse()
    
         // TODO: Document
        Flow.Publisher<ByteBuffer> asPublisher();
    }
}