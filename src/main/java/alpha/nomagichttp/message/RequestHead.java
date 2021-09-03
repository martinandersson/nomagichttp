package alpha.nomagichttp.message;

import alpha.nomagichttp.event.RequestHeadReceived;

/**
 * A "raw" request head where each component can be retrieved as observed on the
 * wire.<p>
 * 
 * This type is emitted as an attachment to the {@link RequestHeadReceived}
 * event. An API for accessing a parsed and <i>accepted</i> request head is
 * embedded in the API of {@link Request}.<p>
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
    String target();
    
    /**
     * Returns the HTTP version token.
     * 
     * @return the HTTP version token
     */
    String httpVersion();
    
    @Override
    Request.Headers headers();
}