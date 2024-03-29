package alpha.nomagichttp.message;

import java.io.Serial;

/**
 * Parsing a request-line from a request head failed.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class RequestLineParseException extends AbstractParseException
{
    @Serial
    private static final long serialVersionUID = 1L;
    
    /**
     * Initializes this object.<p>
     *
     * If the previous character does not exist, pass in a negative value.
     * 
     * @param message    passed as-is to {@link Throwable#Throwable(String)}
     * @param prev       previous character before encountering the error
     * @param curr       current character when encountering the error
     * @param pos        byte position when encountering the error
     * @param byteCount  the number of bytes read from the upstream
     */
    public RequestLineParseException(
            String message, byte prev, byte curr, int pos, int byteCount) {
        super(message, prev, curr, pos, byteCount);
        assert pos >= 0 : "We ought to know the position when parsing a request-line.";
    }
}