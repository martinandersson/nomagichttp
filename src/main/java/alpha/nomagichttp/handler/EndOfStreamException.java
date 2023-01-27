package alpha.nomagichttp.handler;

/**
 * Channel read operation has unexpectedly reached end-of-stream.<p>
 * 
 * This only happens when a content-length for a request body was set and the
 * channel reader reached end-of-stream before reading all expected bytes.<p>
 * 
 * For a streaming request body that has no fixed length, an end-of-stream is
 * actually expected. In this case the request body consumer will observe an
 * empty bytebuffer.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class EndOfStreamException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    
    /**
     * Constructs an {@code EndOfStreamException}.
     */
    public EndOfStreamException() {
        // Empty
    }
}