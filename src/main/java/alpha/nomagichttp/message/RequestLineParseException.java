package alpha.nomagichttp.message;

/**
 * Parsing a request-line from a request head failed.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class RequestLineParseException extends AbstractParseException
{
    private static final long serialVersionUID = 1L;
    
    /**
     * Initializes this object.
     *
     * If the previous character does not exist, pass in a negative value.
     * 
     * @param message passed as-is to {@link Throwable#Throwable(String)}
     * @param prev    previous character before encountering the error
     * @param curr    current character when encountering the error
     * @param pos     byte position when encountering the error
     */
    public RequestLineParseException(
            String message, byte prev, byte curr, int pos) {
        super(message, prev, curr, pos);
        assert pos > 0 : "We ought to know the position when parsing request-line.";
    }
}