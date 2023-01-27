package alpha.nomagichttp.util;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.event.EventHub;
import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.message.Attributes;
import jdk.incubator.concurrent.ScopedValue;

import java.util.NoSuchElementException;
import java.util.function.Supplier;

/**
 * A namespace for scoped values.<p>
 * 
 * The methods found herein yield instances only to code executing within a
 * certain scope, for example the request object can be retrieved from a deeply
 * nested code block executed by a request handler without itself having the
 * object passed to it explicitly as an argument.<p>
 * 
 * Code calling from outside the required scope will return exceptionally with
 * {@link NoSuchElementException}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @see ScopedValue
 */
public final class ScopedValues
{
    private ScopedValues() {
        // Empty
    }
    
    // TODO: Use service loading to find instances initialized by server impl, not static fields
    //       This will also ensure application code can not rebind.
    
    public static ScopedValue<HttpServer> __HTTP_SERVER = ScopedValue.newInstance();
    
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
     * querying the server's configuration.<p>
     * 
     * <pre>
     *   httpServer().{@link HttpServer#events()
     *     events}().{@link EventHub#dispatch(Object)
     *       dispatch}("Something happened");
     * </pre>
     * 
     * @return the running server instance
     * 
     * @throws NoSuchElementException if the server instance is not bound
     */
    public static HttpServer httpServer() {
        return __HTTP_SERVER.get();
    }
    
    public static ScopedValue<ClientChannel> __CLIENT_CHANNEL = ScopedValue.newInstance();
    
    /**
     * Returns the client channel.<p>
     * 
     * The value will be accessible by code executing within an HTTP exchange.
     * For example before-actions, request handlers and after-actions.<p>
     * 
     * Given how the HTTP exchange is executed using a single virtual thread,
     * and how the client channel may be long-lived, its attributes could be
     * useful to store objects that are not thread-safe and expensive to create.
     * 
     * <pre>
     *   var nf = clientChannel().{@link ClientChannel#attributes()
     *     attributes}().{@link Attributes#getOrCreate(String, Supplier)
     *       getOrCreate}("cache-numberformat", NumberFormat::getInstance);
     * </pre>
     * 
     * @return the running server instance
     * 
     * @throws NoSuchElementException
     *             if the client channel instance is not bound
     */
    public static ClientChannel clientChannel() {
        return __CLIENT_CHANNEL.get();
    }
}