package alpha.nomagichttp.message;

/**
 * Signalled by a {@code Flow.Publisher} to an active subscriber when the
 * subscription is early terminated or the publisher was/is terminating.<p>
 * 
 * An example of the former would be an illegally encountered state related to
 * the subscription alone which terminates the subscription but leaves the
 * publisher still useful to other subscribers. An example of the latter would
 * be a lasting error related to the publisher which terminates all current and
 * future subscriptions.<p>
 * 
 * If the publisher terminates unexpectedly, then all future subscribers receive
 * a {@code ClosedPublisherException} as well. If the publisher terminates in a
 * controlled manner - for example a publisher may only support being subscribed
 * to once - then future subscribers receive an {@code IllegalStateException}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
// TODO: Seeing this when I run echo_headers example. Why? Fix.
// TODO: Move to util package. Enforce publisher can not be resubscribed.
public final class ClosedPublisherException extends RuntimeException
{
    /**
     * Error message used when signalling a {@code Flow.Subscriber} failed.
     */
    public static final String SIGNAL_FAILURE = "Signalling Flow.Subscriber failed.";
    
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