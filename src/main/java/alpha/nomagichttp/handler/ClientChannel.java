package alpha.nomagichttp.handler;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.util.AttributeHolder;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.NetworkChannel;
import java.util.concurrent.CompletionStage;

/**
 * A nexus of operations for querying the state of a client channel and
 * writing HTTP responses.<p>
 * 
 * Any number of responses can be written at any time within the HTTP exchange
 * as long as the channel's write stream remains open.<p>
 * 
 * In most cases, there's only one response to a request of category 2XX
 * (Successful), often referred to as the "final response". But a final response
 * may be preceded by any number of intermittent responses of category 1XX
 * (Informational) (since HTTP 1.1).<p>
 * 
 * A response may also be sent back even before the entire request has been
 * received by the server. This is the expected case for a lot of responses from
 * error categories (4XX, 5XX).<p>
 * 
 * So yes, 90% of the internet is wrong when they label HTTP as a synchronous
 * one-to-one protocol.<p>
 * 
 * There's only one small catch. A response can not be written <i>before</i> a
 * request has begun transmitting to the server, i.e. outside the scope of an
 * active HTTP exchange. This would make the client very confused (
 * <a href="https://tools.ietf.org/html/rfc7230#section-5.6">RFC 7230 §5.6</a>).
 * The HTTP exchange begins as soon as a request starts transmitting and is
 * considered done when both the server's final response body subscription
 * completes and the request body has been either consumed or discarded (see
 * {@link Request.Body}). A new exchange will not commence before the previous
 * one ends. In other words, no form of orchestration is needed by the
 * application. Just don't do something stupid like tucking away the channel
 * reference in a global scope somewhere and sporadically and randomly use it to
 * write responses.<p>
 * 
 * The life-cycle of the channel is managed by the server. The application
 * should have no need to directly use shutdown/close methods in this class.
 * For a graceful close of the client connection, set the "Connection: close"
 * header. More abruptly terminating methods may be scheduled using {@link
 * Response.Builder#mustShutdownOutputAfterWrite(boolean)}
 * and {@link Response.Builder#mustCloseAfterWrite(boolean)}. Invoking {@link
 * #close()} on this class is equivalent to an instant "kill".<p>
 * 
 * When using low-level methods to operate the channel, or when storing
 * attributes on the channel, then have in mind that the "client" in {@code
 * ClientChannel} may be a HTTP proxy which represents many different human end
 * users.<p>
 * 
 * The implementation is thread-safe and mostly non-blocking. Underlying channel
 * life-cycle APIs used to query the state of a channel or close it may block
 * and if so, the block is minuscule.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
// https://stackoverflow.com/a/61117435/1268003
public interface ClientChannel extends Closeable, AttributeHolder
{
    /**
     * Write a response.<p>
     * 
     * This method does not block.<p>
     * 
     * The calling thread may be used to initiate an asynchronous write
     * operation immediately, or the response will be enqueued for future
     * transmission (unbounded queue).<p>
     * 
     * At the time of transmission, a response may be rejected. If it is
     * rejected because a final response has already been transmitted (in parts
     * or in whole) - i.e. the HTTP exchange is no longer active - then the
     * error is logged but otherwise ignored. This is the same also for
     * exceptions that complete a response stage. Otherwise (exchange active), a
     * {@link ResponseRejectedException} will pass through the server's error
     * handler.<p>
     * 
     * The {@link Config#ignoreRejectedInformational() default server behavior}
     * is to ignore failed 1XX (Informational) responses if the reason is
     * because the HTTP client is using an old protocol version.<p>
     * 
     * Only at most one 100 (Continue) response will be sent. Repeated 100
     * (Continue) responses will be ignored. Attempts to send more than two will
     * log a warning on each offense.<p>
     * 
     * Responses will be sent in the same order they are given to this method
     * and {@link #write(CompletionStage)}. It does not matter if a previously
     * enqueued stage did not complete before this method is called with an
     * already built response.<p>
     * 
     * In this example, the already built response will be sent last because it
     * was added last:
     * <pre>{@code
     *   CompletionStage<Response> completesSoon = ...
     *   Response alreadyBuilt = ...
     *   channel.write(completesSoon);
     *   channel.write(alreadyBuilt);
     * }</pre>
     * 
     * Having the response be sent in order of stage completion is as simple as:
     * <pre>{@code
     *   myResponseStage.thenAccept(channel::write);
     * }</pre>
     * 
     * @param response the response
     * 
     * @throws NullPointerException if {@code response} is {@code null}
     */
    void write(Response response);
    
    /**
     * Write a response.<p>
     * 
     * If the stage completes exceptionally, the error will pass through the
     * server's chain of {@link ErrorHandler error handlers}.<p>
     * 
     * All JavaDoc of {@link #write(Response)} applies to this method as
     * well.
     * 
     * @param response the response
     * 
     * @throws NullPointerException if {@code response} is {@code null}
     */
    void write(CompletionStage<Response> response);
    
    /**
     * Same as {@link #write(Response)}, except the response will be put as head
     * in the backing pipeline queue.<p>
     * 
     * This method is useful for error handlers that wish to ensure a
     * semantically correct response order.<p>
     * 
     * This method has no perceived effect if the application never writes
     * multiple responses, i.e. never writes a 1XX (Informational) response. In
     * this case, there is no order to be worried about since only one response
     * will be sent back per HTTP exchange.<p>
     * 
     * Suppose the application schedules multiple responses like so;
     * <pre>
     *   Response willCrash1XX = ...
     *   channel.write(willCrash1XX); // Enqueued as first element
     *   Response safe2XX = ...
     *   channel.write(safe2XX); // Enqueued as second element
     * </pre>
     * 
     * An error handler intercepting the crashing response <i>after</i> the
     * second element has already been added and use the {@code write} method
     * would effectively re-arrange the order. In fact, for this given example,
     * the alternative response would actually be rejected at transmission time
     * because it is now succeeding a 2XX final response. In this example, the
     * correct method to call is {@code writeFirst}.<p>
     * 
     * It may not be the wrong thing, for an error handler to call {@code write}
     * and schedule the response as the pipeline tail. This depends very much on
     * the nature of the response itself and high-level messaging semantics. The
     * safest bet for a <i>generic</i> error handler without this knowledge is
     * to always use {@code writeFirst}.<p>
     * 
     * Note; an error handler will never accidentally write a response to a
     * subsequent HTTP exchange. The next response in the pipeline does not
     * begin transmission until the previous one has been successfully written
     * or the server's error handler execution chain of a failed response has
     * scheduled a new response. Nor does a new HTTP exchange begin
     * before the active exchange has successfully written a final response.<p>
     * 
     * @param response the response
     * 
     * @throws NullPointerException if {@code response} is {@code null}
     */
    void writeFirst(Response response);
    
    /**
     * Same as {@link #write(CompletionStage)}, except the response will be put
     * as head in the backing pipeline queue.<p>
     * 
     * This method is equivalent to:
     * <pre>
     *   response.thenAccept(channel::{@link #writeFirst(Response) writeFirst});
     * </pre>
     * 
     * If the stage completes exceptionally, the error will pass through the
     * server's chain of {@link ErrorHandler error handlers}.
     * 
     * @param response the response
     *
     * @throws NullPointerException if {@code response} is {@code null}
     */
    void writeFirst(CompletionStage<Response> response);
    
    /**
     * Returns {@code true} if the application may assume that the underlying
     * channel's input stream is open, otherwise {@code false}.<p>
     * 
     * Note: This method does not probe the current connection status. There's
     * no such support in the underlying channel's API ({@code
     * AsynchronousSocketChannel}). But as long as the {@code ClientChannel} API
     * is the only API used by the application to end the connection (or close
     * the channel) then the returned boolean should tell the truth.
     * 
     * @return see JavaDoc
     */
    boolean isOpenForReading();
    
    /**
     * Returns {@code true} if the application may assume that the underlying
     * channel's output stream is open, otherwise {@code false}.<p>
     * 
     * Note: This method does not probe the current connection status. There's
     * no such support in the underlying channel's API ({@code
     * AsynchronousSocketChannel}). But as long as the {@code ClientChannel} API
     * is the only API used by the application to end the connection (or close
     * the channel) then the returned boolean should tell the truth.
     * 
     * @return see JavaDoc
     */
    boolean isOpenForWriting();
    
    /**
     * Returns {@code true} if the connection's read and write streams are open,
     * as well as the channel itself.
     * 
     * @return see JavaDoc
     * 
     * @see #isOpenForReading() 
     * @see #isOpenForWriting()
     */
    boolean isEverythingOpen();
    
    /**
     * Shutdown the channel's input/read stream.<p>
     * 
     * If this operation fails or effectively terminates the connection (output
     * stream was also shutdown), then this method propagates to {@link
     * #close()}.<p>
     * 
     * Is NOP if input already shutdown or channel is closed.<p>
     * 
     * A shutdown will interrupt a client request in-flight. After shutdown, no
     * more requests will be received.
     * 
     * @throws IOException if a propagated channel-close failed
     */
    void shutdownInput() throws IOException;
    
    /**
     * Same as {@link #shutdownInput()}, except {@code IOException} is logged
     * but otherwise ignored.
     */
    void shutdownInputSafe();
    
    /**
     * Shutdown the channel's output/write stream.<p>
     * 
     * If this operation fails or effectively terminates the connection (input
     * stream was also shutdown), then this method propagates to {@link
     * #close()}.<p>
     * 
     * Is NOP if output already shutdown or channel is closed.<p>
     * 
     * A shutdown will interrupt a client response in-flight. After shutdown, no
     * more responses can be sent.
     * 
     * @throws IOException if a propagated channel-close failed
     */
    void shutdownOutput() throws IOException;
    
    /**
     * Same as {@link #shutdownOutput()}, except {@code IOException} is logged
     * but otherwise ignored.
     */
    void shutdownOutputSafe();
    
    /**
     * End the channel's connection (stop input/output streams) and then close
     * the channel.<p>
     * 
     * Is NOP if channel is already closed.
     * 
     * @throws IOException if closing the channel failed
     * 
     * @see #shutdownInput()
     * @see #shutdownOutput()
     */
    @Override
    void close() throws IOException;
    
    /**
     * Same as {@link #close()}, except {@code IOException} and any throwable
     * thrown because the channel (or group of channel) is already closed, will
     * be ignored.
     */
    void closeSafe();
    
    /**
     * Returns the underlying Java channel instance.<p>
     * 
     * The returned instance is a proxy that does not support being cast to
     * another implementation of {@code NetworkChannel}.
     * 
     * @return the underlying Java channel instance (never {@code null})
     */
    NetworkChannel getDelegate();
    
    /**
     * Returns the server from which this channel originates.
     * 
     * @return the server from which this channel originates (never {@code null})
     */
    HttpServer getServer();
}