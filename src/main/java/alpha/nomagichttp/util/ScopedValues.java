package alpha.nomagichttp.util;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.event.EventHub;
import jdk.incubator.concurrent.ScopedValue;

import java.util.NoSuchElementException;

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
    
    /**
     * A scoped reference to the running server.<p>
     * 
     * This field is used by the NoMagicHTTP library code to bind the running
     * server instance. Application code ought to statically import and use
     * {@link #httpServer()}.
     */
    // TODO: Use service loading to find instances initialized by server impl, not static fields
    //       This will also ensure application code can not rebind.
    public static ScopedValue<HttpServer> HTTP_SERVER = ScopedValue.newInstance();
    
    /**
     * Returns the server.<p>
     * 
     * The value will be accessible by code executing within a server. For
     * example before-actions, request handlers. The value will also always be
     * accessible by the server's event listeners, even if the event is
     * dispatched from the outside.<p> 
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
        return HTTP_SERVER.get();
    }
}