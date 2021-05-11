package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpServer;

/**
 * Thrown by the server on request head timeout.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @see HttpServer.Config#timeoutIdleConnection() 
 */
public final class RequestHeadTimeoutException extends RuntimeException {
    private static final long serialVersionUID = 1L;
}