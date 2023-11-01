package alpha.nomagichttp.message;

import alpha.nomagichttp.handler.HasResponse;

import java.io.Serial;

import static alpha.nomagichttp.message.Responses.entityTooLarge;

/**
 * Abstract superclass of exceptions thrown because a configured tolerance was
 * exceeded.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public sealed abstract class AbstractSizeException
       extends    RuntimeException
       implements HasResponse
       permits    MaxRequestHeadSizeException,
                  MaxRequestBodyBufferSizeException,
                  MaxRequestTrailersSizeException
{
    @Serial
    private static final long serialVersionUID = 1L;
    
    AbstractSizeException(int configuredMax) {
        // This would be the first thing one reading the log would like to know
        super("Configured max tolerance is " + configuredMax + " bytes.");
    }
    
    /**
     * {@return {@link Responses#entityTooLarge()}}
     */
    @Override
    public final Response getResponse() {
        return entityTooLarge();
    }
}