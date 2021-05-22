package alpha.nomagichttp.util;

import static java.util.Objects.requireNonNull;

/**
 * Signalled to a {@code Flow.Subscriber.onError()} if another method of the
 * subscriber failed.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @see Publishers
 */
public final class SubscriberFailedException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    
    /**
     * Create a {@code SubscriberFailedException} that was caused by a failed
     * {@code onNext()} invocation.
     * 
     * @param cause the cause (as caught from the subscriber)
     * @return a {@code SubscriberFailedException}
     * @throws NullPointerException if {@code cause} is {@code null}
     */
    public static SubscriberFailedException onNext(Throwable cause) {
        return new SubscriberFailedException("onNext", cause);
    }
    
    /**
     * Create a {@code SubscriberFailedException} that was caused by a failed
     * {@code onSubscribe()} invocation.
     * 
     * @param cause the cause (as caught from the subscriber)
     * @return a {@code SubscriberFailedException}
     * @throws NullPointerException if {@code cause} is {@code null}
     */
    public static SubscriberFailedException onSubscribe(Throwable cause) {
        return new SubscriberFailedException("onSubscribe", cause);
    }
    
    private SubscriberFailedException(String method, Throwable cause) {
        super("Signalling Flow.Subscriber." + method + "() failed.", requireNonNull(cause));
    }
}