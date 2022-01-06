package alpha.nomagichttp.message;

/**
 * A "raw" request-line where each component can be retrieved as observed on the
 * wire.<p>
 * 
 * String tokens returned by this interface are never {@code null} nor empty.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public record RawRequestLine(
        String method, String target, String httpVersion,
        long nanoTimeOnStart, int parseLength)
{
    /**
     * Returns the method token.
     * 
     * @return see JavaDoc
     */
    @Override
    public String method() {
        return method;
    }
    
    /**
     * Returns the request-target token.
     * 
     * @return see JavaDoc
     */
    @Override
    public String target() {
        return target;
    }
    
    /**
     * Returns the HTTP version token.
     * 
     * @return see JavaDoc
     */
    @Override
    public String httpVersion() {
        return httpVersion;
    }
    
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
     * Returns the number of bytes processed in order to parse the request-line.
     * 
     * @return see JavaDoc
     */
    public int parseLength() {
        return parseLength;
    }
}