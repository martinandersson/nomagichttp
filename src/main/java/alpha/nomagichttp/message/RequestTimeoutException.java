package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpServer;

/**
 * Thrown by the server on request timeout.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @see HttpServer.Config#timeoutIdleConnection() 
 */
public final class RequestTimeoutException extends RuntimeException {
    private static final long serialVersionUID = 1L;
}