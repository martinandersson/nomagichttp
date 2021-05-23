package alpha.nomagichttp.message;

import alpha.nomagichttp.Config;

/**
 * Thrown by the server or delivered to the request body subscriber, either
 * because the client delayed sending data to the server or the application's
 * body subscriber delayed processing items.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @see Config#timeoutIdleConnection()
 */
public class RequestBodyTimeoutException extends RuntimeException {
    private static final long serialVersionUID = 1L;
}