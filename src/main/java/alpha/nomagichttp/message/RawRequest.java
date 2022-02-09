package alpha.nomagichttp.message;

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
     * A raw request head.
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
            long nanoTimeOnStart, int length)
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
         * Returns the number of bytes processed in order to split the
         * request-line into tokens.
         * 
         * @return see JavaDoc
         */
        @Override
        public int length() {
            return length;
        }
    }
}