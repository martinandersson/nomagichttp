package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.handler.HasResponse;

import java.io.Serial;

import static alpha.nomagichttp.message.Responses.notImplemented;
import static java.util.Objects.requireNonNull;

/**
 * A coding listed in the request's {@value
 * HttpConstants.HeaderName#TRANSFER_ENCODING} header is not supported.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class UnsupportedTransferCodingException
             extends RuntimeException implements HasResponse
{
    @Serial
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
    public String coding() {
        return coding;
    }
    
    /**
     * Returns {@link Responses#notImplemented()}.
     * 
     * @return see Javadoc
     */
    @Override
    public Response getResponse() {
        return notImplemented();
    }
}