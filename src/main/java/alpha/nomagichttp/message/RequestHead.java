package alpha.nomagichttp.message;

import alpha.nomagichttp.events.RequestHeadParsed;

import java.net.http.HttpHeaders;

/**
 * A "raw" request head where each component can be retrieved as observed on the
 * wire.<p>
 * 
 * A complex version of a processed and <i>accepted</i> request head is embedded
 * into the API of {@link Request}. This head-type is emitted together with the
 * {@link RequestHeadParsed} event.<p>
 * 
 * String tokens returned by this interface is never {@code null} or empty.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public interface RequestHead {
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
    
    /**
     * Returns the headers.
     * 
     * @return the headers (never {@code null} but may be empty)
     */
    HttpHeaders headers();
}
