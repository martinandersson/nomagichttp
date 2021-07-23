package alpha.nomagichttp.action;

/**
 * Thrown by {@link ActionRegistry} if an equal action has already been added to
 * the same position (object equality).
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class ActionNonUniqueException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    
    /**
     * Constructs this object.
     * 
     * @param cause passed as-is to {@link Throwable#Throwable(Throwable)}
     */
    public ActionNonUniqueException(Throwable cause) {
        super(cause);
    }
}