package alpha.nomagichttp.handler;

import alpha.nomagichttp.ChannelWriter;
import alpha.nomagichttp.HttpConstants.HeaderName;
import alpha.nomagichttp.message.AttributeHolder;
import alpha.nomagichttp.message.Response;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;

import static alpha.nomagichttp.HttpConstants.HeaderName.CONNECTION;
import static java.util.Objects.requireNonNull;

/**
 * An API for operating the client channel.<p>
 * 
 * The life-cycle of the channel is managed by the server. The application
 * should have no need to shut down any of the channel's streams or close the
 * channel explicitly, which could translate to an abrupt end of the request
 * and/or response in-flight.<p>
 * 
 * For a graceful close of the client connection — which allows an in-flight
 * exchange to complete — set the "Connection: close" header in the final
 * response. For convenience, this interface declares the method
 * {@link #tryAddConnectionClose(Response)}.<p>
 * \
 * None of the shutdown/close methods in this class throws {@link IOException}.
 * It is assumed that there is nothing the application can or would like to do
 * about the exception, nor does such an exception mean that the operation
 * wasn't successful (if the resource can't even close then it's pretty much as
 * dead as dead can be, right? *scratching head*).<p>
 * 
 * If the underlying channel does throw an {@code IOException} (when shutting
 * down a stream or closing) that is <i>not</i> a
 * {@link ClosedChannelException}, then it will be logged on the {@code WARNING}
 * level.<p>
 * 
 * Methods that shut down a stream or close the channel are thread-safe. Methods
 * that query the channel state read non-volatile state. Javadoc implementation
 * notes on the state querying methods are valid as long as no happens-before
 * relationship was established by an external primitive.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public interface ClientChannel extends ChannelWriter, AttributeHolder
{
    /**
     * Ensures that the returned response's {@value HeaderName#CONNECTION}
     * header contains the token "close".<p>
     * 
     * The implementation is equivalent to:<p>
     * 
     * {@snippet :
     *   // @link substring="headers" target="Response#headers()" region
     *   // @link substring="hasConnectionClose" target="BetterHeaders#hasConnectionClose()" region
     *   if (rsp.headers().hasConnectionClose()) {
     *       return rsp;
     *   }
     *   // @end
     *   // @end
     *   // @link substring="toBuilder" target="Response#toBuilder()" region
     *   // @link substring="appendHeaderToken" target="Response.Builder#appendHeaderToken(String, String)" region
     *   // @link substring="build" target="Response.Builder#build()" region
     *   return rsp.toBuilder()
     *             .appendHeaderToken("Connection", "close")
     *             .build();
     *   // @end
     *   // @end
     *   // @end
     * }
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
     * If this method has an effect, the reason {@code why} the header will be
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
                   "Setting \"Connection: close\" because " + why + ".");
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
     * Shutdown the input stream.<p>
     * 
     * A purist developer may be tempted to use this method after having
     * finished reading a request, and there is no intent to run more exchanges
     * over the same connection. Be warned, however, that there are some dumb
     * HTTP clients out there (Jetty and the Darwin-award winner Reactor, at
     * least), which if their corresponding output stream is closed, will
     * immediately close the entire connection without waiting on the final
     * response, even if the response is actively being transmitted (big lol?).
     * To be the good samaritan and save their asses, it is therefore
     * recommended to never use this method.<p>
     * 
     * Is NOP if input already shutdown or channel is closed.
     */
    void shutdownInput();
    
    /**
     * Shutdown the output stream.<p>
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
     */
    void close();
    
    /**
     * Returns {@code true} if the input stream is open.
     * 
     * @implNote
     * Because of non-volatile state, a thread other than the request thread may
     * observe a false positive (assuming that no happens-before relationship is
     * established).
     * 
     * @return see JavaDoc
     */
    boolean isInputOpen();
    
    /**
     * Returns {@code true} if the output stream is open.
     * 
     * @implNote
     * Because of non-volatile state, a thread other than the request thread may
     * observe a false positive (assuming that no happens-before relationship is
     * established).
     * 
     * @return see JavaDoc
     */
    boolean isOutputOpen();
    
    /**
     * Returns {@code true} if the input- and/or output stream is open.
     * 
     * @implNote
     * Because of non-volatile state, a thread other than the request thread may
     * observe a false positive (assuming that no happens-before relationship is
     * established).
     * 
     * @return see JavaDoc
     */
    boolean isAnyStreamOpen();
    
    /**
     * Returns {@code true} if the input- and output streams are both open.
     * 
     * @implNote
     * Because of non-volatile state, a thread other than the request thread may
     * observe a false positive (assuming that no happens-before relationship is
     * established).
     * 
     * @return see JavaDoc
     */
    boolean areBothStreamsOpen();
    
    /**
     * Returns {@code true} if the input- and output streams are both shut down.
     * 
     * @implNote
     * Because of non-volatile state, a thread other than the request thread may
     * observe a false negative (but {@code true} means true).
     * 
     * @return see JavaDoc
     */
    boolean isClosed();
}