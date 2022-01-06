package alpha.nomagichttp.message;

/**
 * Parsing headers from a request head or parsing request trailers failed.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class HeaderParseException extends AbstractParseException
{
    private static final long serialVersionUID = 1L;
    
    /**
     * Initializes this object.<p>
     * 
     * The previous character may not exist, and the position may not be known.
     * If so, pass in a negative value for each.
     * 
     * @param message passed as-is to {@link Throwable#Throwable(String)}
     * @param prev    previous character before encountering the error
     * @param curr    current character when encountering the error
     * @param pos     byte position when encountering the error
     */
    public HeaderParseException(
            String message, byte prev, byte curr, int pos) {
        super(message, prev, curr, pos);
    }
}