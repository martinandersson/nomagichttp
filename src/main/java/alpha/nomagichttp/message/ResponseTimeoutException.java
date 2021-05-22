package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.ErrorHandler;

/**
 * Thrown by the server on response timeout.<p>
 * 
 * The alternative response produced by an error handler as a result of this
 * exception should be produced and written synchronously (i.e., without any
 * further delay) and schedule with the response a command to close the channel
 * after the response ({@link Response#mustCloseAfterWrite()}). This is what
 * {@link ErrorHandler#DEFAULT the default error handler} do. The underlying
 * response pipeline who throws this exception will give up waiting on channel
 * closure after another 5 seconds and proceed to close it.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @see HttpServer.Config#timeoutIdleConnection()
 */
public final class ResponseTimeoutException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    
    /**
     * Constructs a {@code ResponseTimeoutException}.
     * 
     * @param message passed through as-is to {@link Throwable#Throwable(String)}
     */
    public ResponseTimeoutException(String message) {
        super(message);
    }
}