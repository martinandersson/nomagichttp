package alpha.nomagichttp.handler;

import alpha.nomagichttp.ChannelWriter;
import alpha.nomagichttp.Config;
import alpha.nomagichttp.HttpConstants.HeaderName;
import alpha.nomagichttp.message.AttributeHolder;
import alpha.nomagichttp.message.BetterHeaders;
import alpha.nomagichttp.message.Response;

import java.io.IOException;

import static alpha.nomagichttp.HttpConstants.HeaderName.CONNECTION;
import static java.util.Objects.requireNonNull;

/**
 * An API for operating the client channel.<p>
 * 
 * The life-cycle of the channel is managed by the server. The application
 * should have no need to shut down any of its streams or close the channel
 * explicitly, which would translate to an abrupt end of the request and/or
 * response in-flight.<p>
 * 
 * For a graceful close of the client connection — which allows an in-flight
 * exchange to complete — set the "Connection: close" header in the final
 * response. For convenience, this interface declares a util method
 * {@link #tryAddConnectionClose(Response)}.<p>
 * 
 * TODO: This paragraph is legacy. We need to implement v-thread timeouts.<br>
 * The application may desire to {@link #shutdownInput()}, for example to
 * explicitly abort an exchange, or to stop the {@linkplain Config#timeoutRead()
 * read timeout} when sending long-lasting streams.<p>
 * 
 * None of the shutdown/close methods in this class throws {@link IOException},
 * although the underlying channel do. It is assumed (perhaps wrongfully?
 * *scratching head*) that there is nothing the application can or would like to
 * do about the exception, nor would the exception mean that the operation
 * wasn't successful (if the resource can't even close then it's pretty much
 * dead as dead can be), hence the exception is logged on the {@code WARNING}
 * level and then ignored.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public interface ClientChannel extends ChannelWriter, AttributeHolder
{
    /**
     * Ensures that the returned response's {@value HeaderName#CONNECTION}
     * header contains the token "close".<p>
     * 
     * The implementation is equivalent to:
     * <pre>
     *     if (rsp.{@link Response#headers() headers
     *             }().{@link BetterHeaders#hasConnectionClose() hasConnectionClose}()) {
     *         return rsp;
     *     }
     *     return rsp.{@link Response#toBuilder() toBuilder}()
     *               .{@link Response.Builder#appendHeaderToken(String, String)
     *                appendHeaderToken}("Connection", "close")
     *               .{@link Response.Builder#build() build}();
     * </pre>
     * 
     * @param rsp the response
     * 
     * @return the response (possibly a new, modified instance)
     * 
     * @throws NullPointerException
     *             if {@code rsp} is {@code null}
     * @throws IllegalArgumentException
     *             if the response
     *             {@link Response#isInformational() isInformational()}
     * 
     * @see ClientChannel
     */
    static Response tryAddConnectionClose(Response rsp) {
        requireNotInformational(rsp);
        return rsp.headers().hasConnectionClose()
                ? rsp : appendConnectionClose(rsp);
    }
    
    /**
     * Ensures that the returned response's {@value HeaderName#CONNECTION}
     * header contains the token "close".<p>
     * 
     * If this method have an effect, the reason {@code why} the header will be
     * set is first logged using the given {@code logger} and {@code level}. The
     * {@code why} argument is not the final log message, however. It'll be used
     * as a replacement value in this message pattern:
     * <pre>
     *   "Will set \"Connection: close\" because $why."
     * </pre>
     * 
     * @param rsp the response
     * @param logger the logger
     * @param level the logging level
     * @param why the reason
     * 
     * @return the response (possibly a new, modified instance)
     * 
     * @throws NullPointerException
     *             if any argument is {@code null}
     * @throws IllegalArgumentException
     *             if {@code why} {@link String#isBlank() isBlank}()
     * @throws IllegalArgumentException
     *             if the response
     *             {@link Response#isInformational() isInformational}()
     * 
     * @see #tryAddConnectionClose(Response) 
     */
    static Response tryAddConnectionClose(
            Response rsp,
            System.Logger logger, System.Logger.Level level, String why) {
        requireNotInformational(rsp);
        requireNonNull(logger);
        requireNonNull(level);
        if (why.isBlank()) {
            throw new IllegalArgumentException("The why argument is blank.");
        }
        if (rsp.headers().hasConnectionClose()) {
            return rsp;
        }
        logger.log(level, () ->
                   "Will set \"Connection: close\" because " + why + ".");
        return appendConnectionClose(rsp);
    }
    
    private static void requireNotInformational(Response r) {
        if (r.isInformational()) {
            throw new IllegalArgumentException(
                "Can not set \"Connection: close\" on 1XX (Informational) response.");
        }
    }
    
    private static Response appendConnectionClose(Response r) {
        return r.toBuilder().appendHeaderToken(CONNECTION, "close").build();
    }
    
    /**
     * Returns {@code true} if the input stream is open.<p>
     * 
     * This method does not probe the channel's actual status. It simply reads a
     * local flag set by the {@link #shutdownInput()} and {@link #close()}
     * methods.
     * 
     * @return see JavaDoc
     */
    boolean isInputOpen();
    
    /**
     * Returns {@code true} if the output stream is open.<p>
     * 
     * This method does not probe the channel's actual status. It simply reads a
     * local flag set by the {@link #shutdownOutput()} and {@link #close()}
     * methods.
     * 
     * @return see JavaDoc
     */
    boolean isOutputOpen();
    
    /**
     * Shutdown the input stream.<p>
     * 
     * If this operation fails or effectively terminates the connection (output
     * stream was also shutdown), then this method cascades to
     * {@link #close()}.<p>
     * 
     * Be warned that there are some dumb HTTP clients out there (Jetty and the
     * Darwin-award winner Reactor, at least), which if their corresponding
     * output stream is closed, will immediately close the entire connection
     * without waiting on the final response (lol?).<p>
     * 
     * Is NOP if input already shutdown or channel is closed.
     */
    void shutdownInput();
    
    /**
     * Shutdown the output stream.<p>
     * 
     * If this operation fails or effectively terminates the connection (input
     * stream was also shutdown), then this method propagates to {@link
     * #close()}.<p>
     * 
     * Is NOP if output already shutdown or channel is closed.
     */
    void shutdownOutput();
    
    /**
     * Closes the channel.<p>
     * 
     * Invoking this method is equivalent to an instant "kill" of the HTTP
     * connection and any ongoing message exchange, or a "headshot", if you
     * prefer. Only gaming enthusiasts should be using this method.<p>
     * 
     * Is NOP if channel is already closed.
     * 
     * @see #shutdownInput()
     * @see #shutdownOutput()
     */
    void close();
}