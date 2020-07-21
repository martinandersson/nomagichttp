package alpha.nomagichttp.message;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * TODO: Document properly.
 * 
 * Status line (HTTP-version, status-code, reason-phrase) as well
 * as headers and message body.
 * 
 * Server does not call the statusLine() method before the body publisher has started
 * to yield bytes. Then the headers(). This means that the implementation should not
 * allow to be modified after the statusLine() method has been called as the
 * new state would not safely make it to the actual response bytes. It also means
 * that the implementation could if need be, set these values lazily as long as
 * the body hasn't started.
 */
public interface Response
{
    // TODO: Document
    String statusLine();
    
    /**
     * TODO: Document properly
     * Provides all header lines. Header keys may be duplicated. They will be
     * written orderly verbatim.
     * 
     * @return
     */
    Iterable<String> headers();
    
    /**
     * TODO: Document
     * 
     * The server will subscribe and push the content to the other side.
     */
    Flow.Publisher<ByteBuffer> body();
    
    // TODO: Document
    default CompletionStage<Response> asCompletedStage() {
        return CompletableFuture.completedStage(this);
    }
}