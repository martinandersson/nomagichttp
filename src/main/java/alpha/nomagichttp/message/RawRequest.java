package alpha.nomagichttp.message;

import alpha.nomagichttp.event.RequestHeadReceived;

/**
 * A namespace for raw request components.<p>
 *
 * An API for accessing a fully parsed and <i>accepted</i> request head is
 * embedded in the API of {@link Request}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class RawRequest
{
    private RawRequest() {
        // Empty
    }
    
    /**
     * A raw request head.<p>
     * 
     * This type is emitted as an attachment to the {@link RequestHeadReceived}
     * event. It is also retrievable from {@link
     * IllegalRequestBodyException}.
     */
    public record Head(Line line, Request.Headers headers)
            implements HeaderHolder {
        // Empty
    }
    
    /**
     * A raw request-line where each component can be retrieved as observed on
     * the wire.<p>
     * 
     * String tokens returned by this interface are never {@code null} nor
     * empty.
     */
    public record Line(
            String method, String target, String httpVersion,
            long nanoTimeOnStart, int parseLength)
    {
        /**
         * Returns the value from {@link System#nanoTime()} polled just before
         * parsing the request-line began.
         * 
         * @return see JavaDoc
         */
        @Override
        public long nanoTimeOnStart() {
            return nanoTimeOnStart;
        }
        
        /**
         * Returns the number of bytes processed in order to parse the
         * request-line.
         * 
         * @return see JavaDoc
         */
        @Override
        public int parseLength() {
            return parseLength;
        }
    }
}