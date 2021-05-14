package alpha.nomagichttp.message;

/**
 * Signalled by a {@code Flow.Publisher} provided by the NoMagicHTTP library to
 * a subscriber when the publisher is terminating.<p>
 * 
 * After the error, the publisher can not be subscribed to again. Such attempt
 * will cause an {@code IllegalStateException} to be signalled to the
 * subscriber.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class ClosedPublisherException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    
    /**
     * Constructs a {@code ClosedPublisherException}.
     */
    public ClosedPublisherException() {
        // Empty
    }
    
    /**
     * Constructs a {@code ClosedPublisherException}.
     * 
     * @param message passed as-is to {@link Throwable#Throwable(String)}
     */
    public ClosedPublisherException(String message) {
        super(message);
    }
    
    /**
     * Constructs a {@code ClosedPublisherException}.
     * 
     * @param message  passed as-is to {@link Throwable#Throwable(String)}
     * @param cause    passed as-is to {@link Throwable#Throwable(String, Throwable)}
     */
    public ClosedPublisherException(String message, Throwable cause) {
        super(message, cause);
    }
}