package alpha.nomagichttp.message;

/**
 * Abstract superclass of exceptions thrown because a configured tolerance was
 * exceeded.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
abstract class AbstractSizeException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    
    AbstractSizeException(int configuredMax) {
        // This would be the first thing one reading the log would like to know
        super("Configured max tolerance is " + configuredMax + " bytes.");
    }
}