package alpha.nomagichttp.util;

import alpha.nomagichttp.ChannelWriter;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.event.EventHub;
import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.message.Response;

import java.util.NoSuchElementException;

/**
 * A namespace for scoped values.<p>
 * 
 * The methods found herein yield instances only to code executing within a
 * particular scope, for example the request object can be retrieved from a
 * deeply nested code block executed by a request handler without itself having
 * the object passed to it explicitly as an argument.<p>
 * 
 * Code calling from outside the required scope will return exceptionally with
 * {@link NoSuchElementException}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
// TODO: Rename to NoMagicHTTP/Standard/Library ScopedValues ?
public final class ScopedValues
{
    private ScopedValues() {
        // Empty
    }
    
    // TODO: Use service loading to find instances initialized by server impl, not static fields.
    //       This will also ensure application code can not rebind.
    
    /** TEMPORARILY */
    public static DummyScopedValue<HttpServer>
            __HTTP_SERVER = DummyScopedValue.newInstance();
    
    /**
     * Returns the server.<p>
     * 
     * The value will be accessible by code executing within a server. For
     * example before-actions, request handlers and after-actions.<p>
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
     * @return the running server
     * 
     * @throws NoSuchElementException if the server instance is not bound
     */
    public static HttpServer httpServer() {
        return __HTTP_SERVER.get();
    }
    
    /** TEMPORARILY */
    public static  DummyScopedValue<ClientChannel>
            __CHANNEL = DummyScopedValue.newInstance();
    
    /**
     * Returns the client channel.<p>
     * 
     * The value will be accessible by code executing within an HTTP
     * exchange.
     * 
     * @return the client channel
     * 
     * @throws NoSuchElementException
     *             if the client channel instance is not bound
     * 
     * @see ChannelWriter#write(Response)
     * @see <a href="https://stackoverflow.com/q/75047540/1268003">StackOverflow Question</a>
     */
    public static ClientChannel channel() {
        return __CHANNEL.get();
    }
}