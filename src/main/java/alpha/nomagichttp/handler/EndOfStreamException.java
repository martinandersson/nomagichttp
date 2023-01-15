package alpha.nomagichttp.handler;

/**
 * Channel has reached end-of-stream.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class EndOfStreamException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    
    /**
     * Constructs an {@code EndOfStreamException}.
     */
    public EndOfStreamException() {
        // Intentionally empty
    }
}