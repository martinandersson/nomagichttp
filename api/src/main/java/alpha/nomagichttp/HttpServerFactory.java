package alpha.nomagichttp;

import alpha.nomagichttp.handler.ErrorHandler;

/**
 * Factory of {@code HttpServer}.<p>
 * 
 * The NoMagicHTTP library does not support custom implementations of the API,
 * and application code should have no use of this type. It is only public
 * because it is a requirement by Java's service-provider mechanism.
 */
@FunctionalInterface
public interface HttpServerFactory {
    /**
     * Creates the server.<p>
     * 
     * This method is used by the static method
     * {@link HttpServer#create(Config, ErrorHandler...) HttpServer.create()} to
     * create the server instance.
     * 
     * @param config of server
     * @param eh     error handler(s)
     * 
     * @return an HTTP server instance
     * 
     * @throws NullPointerException
     *             if an argument or array element is {@code null}
     */
    HttpServer create(Config config, ErrorHandler... eh);
}