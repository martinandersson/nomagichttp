package alpha.nomagichttp.message;

/**
 * A namespace for raw request components.<p>
 * 
 * With raw is meant tokenized strings as received on the wire. An API for
 * accessing a parsed and <i>accepted</i> request head is embedded in the API of
 * {@link Request}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class RawRequest
{
    private RawRequest() {
        // Empty
    }
    
    /**
     * Constructs a raw request head.
     * 
     * @param line the request line
     * @param headers the request headers
     */
    public record Head(RawRequest.Line line, Request.Headers headers)
           implements HasHeaders {
        // Empty
    }
    
    /**
     * A raw request-line.<p>
     * 
     * Returned string tokens are never {@code null} nor empty.
     * 
     * @param method of request
     * @param target of request
     * @param httpVersion of request
     * @param nanoTimeOnStart see {@link #nanoTimeOnStart()}
     * @param length see {@link #length()}
     */
    public record Line(
            String method, String target, String httpVersion,
            long nanoTimeOnStart, int length)
    {
        /**
         * Constructs this object.
         * 
         * @param method of request
         * @param target of request
         * @param httpVersion of request
         * @param nanoTimeOnStart see {@link #nanoTimeOnStart()}
         * @param length see {@link #length()}
         */
        public Line {
            requireNonEmpty(method, "method");
            requireNonEmpty(target, "target");
            requireNonEmpty(httpVersion, "httpVersion");
        }
        
        private void requireNonEmpty(String val, String name) {
            if (val.isEmpty()) {
                throw new IllegalArgumentException(name + " is empty.");
            }
        }
        
        /**
         * {@return the value from {@link System#nanoTime()} polled just before
         * parsing the request-line began}
         */
        @Override
        public long nanoTimeOnStart() {
            return nanoTimeOnStart;
        }
        
        /**
         * {@return the number of bytes processed in order to split the
         * request-line into tokens}
         */
        @Override
        public int length() {
            return length;
        }
    }
}