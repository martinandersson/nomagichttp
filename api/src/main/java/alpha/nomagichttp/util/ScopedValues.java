package alpha.nomagichttp.util;

import alpha.nomagichttp.ChannelWriter;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.event.EventHub;
import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.message.Response;

import java.util.NoSuchElementException;

import static java.lang.ScopedValue.newInstance;

/**
 * A namespace for scoped values.<p>
 * 
 * The scoped instances are bound within a defined scope.<p>
 * 
 * Generally, one should prefer using static methods declared in this class to
 * access bound values.<p>
 * 
 * The static fields must never be rebound to {@code null}, as the server
 * itself may access the values, and is free to assume the presence of a value.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
// TODO: Rename to NoMagicHTTP/Standard/Library ScopedValues ?
public final class ScopedValues {
    private ScopedValues() {
        // Empty
    }
    
    /**
     * {@return the running server}<p>
     * 
     * The value will be accessible by code running within a server. For
     * example; before-actions, request handlers and after-actions.<p>
     * 
     * The value will always be accessible by the server's event listeners, even
     * if the event is dispatched [and consequently executed by a thread] from
     * outside the server.<p> 
     * 
     * Accessing the server may be useful for things like dispatching events and
     * querying the server's configuration.
     * 
     * <pre>
     *   httpServer().{@link HttpServer#events()
     *     events}().{@link EventHub#dispatch(Object)
     *       dispatch}("Something happened");
     * </pre>
     * 
     * @throws NoSuchElementException if the server instance is not bound
     */
    public static HttpServer httpServer() {
        return HTTP_SERVER.get();
    }
    
    /**
     * {@return the client channel}<p>
     * 
     * The value will be accessible by code running within an HTTP exchange.
     * 
     * @throws NoSuchElementException
     *             if the client channel instance is not bound
     * 
     * @see ChannelWriter#write(Response)
     * @see <a href="https://stackoverflow.com/q/75047540/1268003">StackOverflow Question</a>
     */
    public static ClientChannel channel() {
        return CHANNEL.get();
    }
    
    /**
     * Contains the {@code HttpServer} instance, if bound.
     * 
     * @see #httpServer()
     */
    public static final ScopedValue<HttpServer> HTTP_SERVER = newInstance();
    
    /**
     * Contains the {@code ClientChannel} instance, if bound.
     * 
     * @see #channel()
     */
    public static final ScopedValue<ClientChannel> CHANNEL = newInstance();
}
