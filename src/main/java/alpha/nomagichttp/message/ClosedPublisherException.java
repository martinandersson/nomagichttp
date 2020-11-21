package alpha.nomagichttp.message;

/**
 * Signalled by a {@code Flow.Publisher} to an active subscriber when
 * closing/shutting down, for example when the server closes a channel and
 * there's still a subscriber subscribing to the request body.<p>
 * 
 * Note that if a subscriber subscribes to an already closed publisher it should
 * immediately receive an {@code IllegalStateException}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Request.Body
 */
public final class ClosedPublisherException extends RuntimeException
{
    public static final String SIGNAL_FAILURE = "Signalling Flow.Subscriber failed.";
    
    public ClosedPublisherException() {
        // Empty
    }
    
    public ClosedPublisherException(String message) {
        super(message);
    }
    
    public ClosedPublisherException(Throwable cause) {
        super(cause);
    }
    
    public ClosedPublisherException(String message, Throwable cause) {
        super(message, cause);
    }
}