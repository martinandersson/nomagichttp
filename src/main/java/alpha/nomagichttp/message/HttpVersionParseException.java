package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.handler.ErrorHandler;

import static java.util.Objects.requireNonNull;

/**
 * Thrown by the server if the HTTP-version field in the request head could not
 * be parsed into a {@link HttpConstants.Version} object.<p>
 * 
 * The {@link ErrorHandler#DEFAULT default error handler} will translate this
 * exception to a {@link Responses#badRequest() 400 Bad Request}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class HttpVersionParseException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    
    private final String requestFieldValue;
    
    /**
     * Constructs a {@code HttpVersionParseException}.
     * 
     * @param requestFieldValue which attempted to parse
     * @param cause passed as-is to {@link Throwable#Throwable(Throwable)}
     * 
     * @throws NullPointerException if {@code requestFieldValue} is {@code null}
     */
    public HttpVersionParseException(String requestFieldValue, Throwable cause) {
        super(cause);
        this.requestFieldValue = requireNonNull(requestFieldValue);
    }
    
    /**
     * Constructs a {@code HttpVersionParseException}.
     * 
     * @param requestFieldValue which attempted to parse
     * @param message passed as-is to {@link Throwable#Throwable(String, Throwable)}
     * 
     * @throws NullPointerException if {@code requestFieldValue} is {@code null}
     */
    public HttpVersionParseException(String requestFieldValue, String message) {
        super(message);
        this.requestFieldValue = requireNonNull(requestFieldValue);
    }
    
    /**
     * Returns the HTTP-version field value from the request (which did not
     * successfully parse).
     * 
     * @return the HTTP-version field value from the request (never {@code null})
     */
    public final String requestFieldValue() {
        return requestFieldValue;
    }
}