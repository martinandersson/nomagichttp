package alpha.nomagichttp.handler;

import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.Responses;

import static alpha.nomagichttp.message.Responses.badRequest;

/**
 * A channel read operation has <i>unexpectedly</i> reached end-of-stream.<p>
 * 
 * This only happens when a content-length for a request body was set and the
 * channel reader reached end-of-stream before reading all expected bytes.<p>
 * 
 * For a streaming request body that has no fixed length, end-of-stream is
 * expected. In this case and only in this case, the request body consumer will
 * observe an empty bytebuffer.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class EndOfStreamException
             extends RuntimeException implements HasResponse
{
    private static final long serialVersionUID = 1L;
    
    /**
     * Constructs an {@code EndOfStreamException}.
     */
    public EndOfStreamException() {
        // Empty
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
