package alpha.nomagichttp.message;

import alpha.nomagichttp.handler.WithResponse;

import static alpha.nomagichttp.message.Responses.entityTooLarge;

/**
 * Abstract superclass of exceptions thrown because a configured tolerance was
 * exceeded.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public sealed abstract class AbstractSizeException
       extends    RuntimeException
       implements WithResponse
       permits    MaxRequestHeadSizeException,
                  MaxRequestBodyBufferSizeException,
                  MaxRequestTrailersSizeException
{
    private static final long serialVersionUID = 1L;
    
    AbstractSizeException(int configuredMax) {
        // This would be the first thing one reading the log would like to know
        super("Configured max tolerance is " + configuredMax + " bytes.");
    }
    
    /**
     * Returns {@link Responses#entityTooLarge()}.
     * 
     * @return see Javadoc
     */
    @Override
    public final Response getResponse() {
        return entityTooLarge();
    }
}