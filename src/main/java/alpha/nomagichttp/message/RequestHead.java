package alpha.nomagichttp.message;

import alpha.nomagichttp.events.RequestHeadParsed;

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
    
    /**
     * Returns the value from {@link System#nanoTime()} polled when the first
     * char of the request head was read and processed.<p>
     * 
     * Useful to compute the time it took for the request head to be parsed
     * (parsing occurs concurrently as chars are read, subject to upstream
     * buffering; may or may not include time spent on the wire).
     * <pre>
     *   long elapsedNanos = System.nanoTime() - head.nanoTimeOnStart();
     * </pre>
     * 
     * @return a previous value of the running Java Virtual Machine's
     * high-resolution time source, in nanoseconds, as read when the first
     * request head char was read and processed
     */
    long nanoTimeOnStart();
}
