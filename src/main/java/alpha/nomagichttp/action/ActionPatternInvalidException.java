package alpha.nomagichttp.action;

/**
 * Thrown by {@link ActionRegistry} if an action pattern is invalid.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class ActionPatternInvalidException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    
    /**
     * Constructs this object.
     *
     * @param cause passed as-is to {@link Throwable#Throwable(Throwable)}
     */
    public ActionPatternInvalidException(Throwable cause) {
        super(cause);
    }
}