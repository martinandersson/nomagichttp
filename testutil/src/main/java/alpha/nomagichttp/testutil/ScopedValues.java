package alpha.nomagichttp.testutil;

import alpha.nomagichttp.HttpServer;

import static alpha.nomagichttp.Config.DEFAULT;
import static alpha.nomagichttp.util.ScopedValues.HTTP_SERVER;
import static java.lang.ScopedValue.CallableOp;
import static java.lang.ScopedValue.callWhere;
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
     * @throws X
     *             if the operation completes with an exception
     */
    public static <R, X extends Throwable> R
            whereServerIsBound(CallableOp<? extends R, X> op) throws X {
        var server = mock(HttpServer.class);
        when(server.getConfig()).thenReturn(DEFAULT);
        return callWhere(HTTP_SERVER, server, op);
    }
}