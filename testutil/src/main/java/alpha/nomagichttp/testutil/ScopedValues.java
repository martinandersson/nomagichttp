package alpha.nomagichttp.testutil;

import alpha.nomagichttp.HttpServer;

import java.util.concurrent.Callable;

import static alpha.nomagichttp.Config.DEFAULT;
import static alpha.nomagichttp.util.ScopedValues.__HTTP_SERVER;
import static jdk.incubator.concurrent.ScopedValue.where;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Utils for binding values.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class ScopedValues {
    private ScopedValues() {
        // Empty
    }
    
    /**
     * Binds {@code ScopedValues.httpServer()} to a mocked server instance.<p>
     * 
     * The mock will use the library's default configuration.<p>
     * 
     * This method should only be used by small quote unquote "cold" tests.
     * 
     * @param op operation to run
     * @param <R> result type
     * 
     * @return the operation's result
     * 
     * @throws NullPointerException
     *             if {@code op} is {@code null}
     * @throws Exception
     *             if the operation completes with an exception
     */
    public static <R> R whereServerIsBound(Callable<? extends R> op)
            throws Exception {
        var server = mock(HttpServer.class);
        when(server.getConfig()).thenReturn(DEFAULT);
        return where(__HTTP_SERVER, server, op);
    }
}