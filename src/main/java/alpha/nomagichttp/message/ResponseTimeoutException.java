package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpServer;

/**
 * Thrown by the server on response timeout.
 *
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @see HttpServer.Config#timeoutIdleConnection()
 */
public final class ResponseTimeoutException extends RuntimeException {
    private static final long serialVersionUID = 1L;
}