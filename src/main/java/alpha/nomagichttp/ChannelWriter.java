package alpha.nomagichttp;

import alpha.nomagichttp.action.AfterAction;
import alpha.nomagichttp.action.BeforeAction;
import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.handler.ResponseRejectedException;
import alpha.nomagichttp.message.BadHeaderException;
import alpha.nomagichttp.message.ContentHeaders;
import alpha.nomagichttp.message.IllegalResponseBodyException;
import alpha.nomagichttp.message.ResourceByteBufferIterable;
import alpha.nomagichttp.message.Response;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * A transmitter of data from a response to a byte channel.<p>
 * 
 * The life-cycle has been documented in {@link ClientChannel}.<p>
 * 
 * The implementation is not thread-safe.<p>
 * 
 * The implementation does not implement {@code hashCode} and {@code equals}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public interface ChannelWriter
{
    /**
     * Writes a response.<p>
     * 
     * Normally, the call chain of {@link BeforeAction}s and the
     * {@link RequestHandler}, and the call chain of {@link ErrorHandler}s,
     * returns a final response to the server which in turn uses this method to
     * write the response. Application code should generally not have a need to
     * use this method, other than to write interim responses, or for explicit
     * writes (see example in JavaDoc of {@link RequestHandler}).<p>
     * 
     * An {@link AfterAction} must never use this method. This would result in
     * infinite recursion and undefined application behavior. The writer
     * implementation has no obligation to protect itself from recursion.<p>
     * 
     * Only at most one 100 (Continue) response will be sent. Repeated 100
     * (Continue) responses are ignored. Also ignored — unless configured
     * differently by {@link Config#discardRejectedInformational()} — is any
     * attempt to write a 1XX (Informational) to a client older than
     * HTTP/1.1.<p>
     * 
     * For both of the aforementioned cases, this method short-circuits and
     * returns 0. Otherwise, the method will invoke relevant
     * {@link AfterAction}s (who may modify the response), and then write the
     * response to the underlying channel (using relative get methods of the
     * response body's bytebuffers), and then — assuming the length of the
     * response is finite — return the number of bytes written, which will be a
     * positive number.
     * 
     * @param rsp response to send (must not be {@code null})
     * 
     * @return the number of bytes written
     * 
     * @throws NullPointerException
     *             if {@code rsp} is {@code null}
     * @throws IllegalStateException
     *             if the HTTP exchange to which the writer was bound, is over
     * @throws IllegalStateException
     *             if a previous response began writing, but never finished
     *             (channel is corrupt)
     * @throws IllegalStateException
     *             if {@code response} {@link Response#isFinal() isFinal} and a
     *             final response has already been sent
     * @throws ResponseRejectedException
     *             for a {@link ResponseRejectedException.Reason Reason}
     * @throws BadHeaderException
     *             for the same reasons as specified in
     *             {@link ContentHeaders#contentLength()}
     * @throws IllegalArgumentException
     *             if message framing is invalid
     *             (for example, a Content-Length header has been set in a 1xx response)
     * @throws IllegalResponseBodyException
     *             if the body is not knowingly
     *             {@link ResourceByteBufferIterable#isEmpty() empty},
     *             and the status code is one of
     *             1XX (Informational), 204 (No Content), 304 (Not Modified)
     * @throws IllegalResponseBodyException
     *             if the request — to which the response is a response — has
     *             HTTP method {@code HEAD} or {@code CONNECT}
     * @throws InterruptedException
     *             if interrupted while waiting on a write-lock
     *             (may only be applicable for a file-backed response body)
     * @throws TimeoutException
     *             if waiting on a write-lock timed out
     *             (may only be application for a file-backed response body)
     * @throws IOException
     *             if an I/O error occurs
     *             (the origin can be file-backed response body, as well as the
     *              destination channel)
     */
    long write(Response rsp)
            throws InterruptedException, TimeoutException, IOException;
    
    /**
     * Returns whether a final response has been sent.
     * 
     * @return see JavaDoc
     */
    boolean wroteFinal();
    
    /**
     * Returns the number of bytes written to the underlying channel.<p>
     * 
     * The count is never reset. It reflects the total number of bytes written
     * since the beginning of the HTTP exchange until this method is called.
     * 
     * @return see JavaDoc
     */
    long byteCount();
    
    /**
     * Schedules the client channel to close after the final response.<p>
     * 
     * The client channel may close for other reasons, such as the response
     * containing the "Connection: close" header. If this wouldn't be the case
     * however, this method guarantees that the response will set the
     * "Connection: close" header and subsequently close the channel.<p>
     * 
     * This method can be used by code whenever it does not have access to- or
     * can not otherwise manipulate an outbound response object.<p>
     * 
     * An alternative would be to simply shut down the channel's input stream,
     * which will also cause the writer to ensure the "Connection: close" header
     * is set. However, there are some incredibly dumb HTTP clients out there
     * (Jetty and Reactor, at least), which if their corresponding output stream
     * is closed, will cause the client to immediately close the entire
     * connection without waiting on the final response (lol?).<p>
     * 
     * The {@code reason} argument should begin with a lower-cased letter as it
     * will be appended to another string.<p>
     * 
     * This method is NOP if the close has already been scheduled.
     * 
     * @param reason to log if this method has an effect
     */
    void scheduleClose(String reason);
}