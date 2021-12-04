package alpha.nomagichttp.message;

/**
 * Thrown by a subtype of {@link BetterHeaders} if parsing a header value from
 * string to another Java type fails.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class BadHeaderException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    
    /**
     * Constructs a {@code BadHeaderException}.
     * 
     * @param message passed as-is to {@link Throwable#Throwable(String)}
     */
    public BadHeaderException(String message) {
        super(message);
    }
    
    /**
     * Constructs a {@code BadHeaderException}.
     * 
     * @param message  passed as-is to {@link Throwable#Throwable(String)}
     * @param cause    passed as-is to {@link Throwable#Throwable(String, Throwable)}
     */
    public BadHeaderException(String message, Throwable cause) {
        super(message, cause);
    }
}