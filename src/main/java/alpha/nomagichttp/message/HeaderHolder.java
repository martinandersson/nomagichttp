package alpha.nomagichttp.message;

/**
 * Adds the method {@link #headers()}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public interface HeaderHolder {
    /**
     * Returns the HTTP headers.
     * 
     * @return the HTTP headers (never {@code null})
     */
    CommonHeaders headers();
}