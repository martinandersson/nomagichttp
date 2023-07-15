package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.handler.ErrorHandler;

import static java.util.Objects.requireNonNull;

/**
 * A coding listed in the request's {@value
 * HttpConstants.HeaderName#TRANSFER_ENCODING} header is not supported.<p>
 * 
 * The {@linkplain ErrorHandler#BASE base error handler} translates this
 * exception to a {@link Responses#notImplemented() 501 (Not Implemented)}
 * response.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class UnsupportedTransferCodingException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    
    private final String coding;
    
    /**
     * Initializes this object.
     * 
     * @param coding the unsupported coding
     * @throws NullPointerException if {@code coding} is {@code null}
     */
    public UnsupportedTransferCodingException(String coding) {
        super("Unsupported Transfer-Encoding: " + requireNonNull(coding));
        this.coding = coding;
    }
    
    /**
     * Returns the unsupported coding token.<p>
     * 
     * The request's {@value HttpConstants.HeaderName#TRANSFER_ENCODING} header
     * may contain even more unsupported codings.
     * 
     * @return see JavaDoc
     */
    public final String coding() {
        return coding;
    }
}