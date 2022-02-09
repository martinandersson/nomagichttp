package alpha.nomagichttp.message;

import alpha.nomagichttp.handler.ErrorHandler;

/**
 * A byte decoder failed.<p>
 * 
 * A byte decoder decodes a stream of bytes into another stream of bytes, e.g.
 * HTTP/1.1 dechunking.<p>
 * 
 * The {@link ErrorHandler#DEFAULT default error handler} will translate this
 * exception to a {@link Responses#badRequest() 400 Bad Request}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class DecoderException extends RuntimeException
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
}