package alpha.nomagichttp.message;

import alpha.nomagichttp.Config;

/**
 * Thrown by the server on request head timeout.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @see Config#timeoutIdleConnection() 
 */
public final class RequestHeadTimeoutException extends RuntimeException {
    private static final long serialVersionUID = 1L;
}