package alpha.nomagichttp.message;

/**
 * Adds the method {@link #headers()}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public interface HeaderHolder {
    /**
     * Returns the HTTP headers.<p>
     * 
     * The headers returned are derived from lines between the start-line and
     * the body of the HTTP message. They do not include trailing headers.
     * 
     * @see Request#trailers()
     * @return the HTTP headers (never {@code null})
     */
    ContentHeaders headers();
}