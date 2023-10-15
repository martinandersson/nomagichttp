package alpha.nomagichttp.action;

import java.io.Serial;

/**
 * Thrown by {@link ActionRegistry} if an equal action has already been added to
 * the same position (object equality).
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class ActionNonUniqueException extends RuntimeException
{
    @Serial
    private static final long serialVersionUID = 1L;
    
    /**
     * Constructs this object.
     * 
     * @param msg passed as-is to {@link Throwable#Throwable(String)}
     */
    public ActionNonUniqueException(String msg) {
        super(msg);
    }
}