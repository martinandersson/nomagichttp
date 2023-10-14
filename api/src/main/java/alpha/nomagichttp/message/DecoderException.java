package alpha.nomagichttp.message;

import alpha.nomagichttp.handler.WithResponse;

import static alpha.nomagichttp.message.Responses.badRequest;

/**
 * A byte decoder failed.<p>
 * 
 * A byte decoder decodes a stream of bytes into another stream of bytes, e.g.
 * HTTP/1.1 dechunking.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class DecoderException
             extends RuntimeException implements WithResponse
{
    private static final long serialVersionUID = 1L;
    
    /**
     * Initializes this object.
     * 
     * @param message passed through to {@link Throwable#Throwable(String)}
     */
    public DecoderException(String message) {
        super(message);
    }
    
    /**
     * Initializes this object.
     * 
     * @param cause passed through to {@link Throwable#Throwable(String, Throwable)}
     */
    public DecoderException(Throwable cause) {
        super(cause);
    }
    
    /**
     * Initializes this object.
     * 
     * @param message passed through to {@link Throwable#Throwable(String, Throwable)}
     * @param cause passed through to {@link Throwable#Throwable(String, Throwable)}
     */
    public DecoderException(String message, Throwable cause) {
        super(message, cause);
    }
    
    /**
     * Returns {@link Responses#badRequest()}.
     * 
     * @return see Javadoc
     */
    @Override
    public Response getResponse() {
        return badRequest();
    }
}