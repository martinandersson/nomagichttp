package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.handler.HasResponse;

import java.io.Serial;

import static alpha.nomagichttp.message.Responses.badRequest;
import static java.util.Objects.requireNonNull;

/**
 * Thrown by the server if the HTTP-version field in the request head could not
 * be parsed into a {@link HttpConstants.Version} object.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class HttpVersionParseException
             extends RuntimeException implements HasResponse
{
    @Serial
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
     * Returns the HTTP-version field value from the request.<p>
     * 
     * The returned valued is the raw value, which could not be successfully
     * parsed.
     * 
     * @return the HTTP-version field value from the request (never {@code null})
     */
    public String getRequestFieldValue() {
        return requestFieldValue;
    }
    
    /**
     * {@return {@link Responses#badRequest()}}
     */
    @Override
    public Response getResponse() {
        return badRequest();
    }
}
