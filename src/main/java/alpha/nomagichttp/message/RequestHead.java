package alpha.nomagichttp.message;

import alpha.nomagichttp.events.RequestHeadParsed;

/**
 * A "raw" request head where each component can be retrieved as observed on the
 * wire.<p>
 * 
 * A complex version of a parsed and <i>accepted</i> request head is embedded in
 * the API of {@link Request}. This head-type is emitted together with the
 * {@link RequestHeadParsed} event.<p>
 * 
 * String tokens returned by this interface is never {@code null} or empty.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public interface RequestHead extends HeaderHolder {
    /**
     * Returns the method token.
     * 
     * @return the method token
     */
    String method();
    
    /**
     * Returns the request-target token.
     * 
     * @return the request-target token
     */
    String requestTarget();
    
    /**
     * Returns the HTTP version token.
     * 
     * @return the HTTP version token
     */
    String httpVersion();
}