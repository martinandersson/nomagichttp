package alpha.nomagichttp;

import alpha.nomagichttp.handler.ExceptionHandler;

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
     * Creates a new {@code HttpServer}.<p>
     * 
     * This method should only be used by the static method
     * {@link HttpServer#create(Config, ExceptionHandler...) HttpServer.create()}.
     * 
     * @param config of server
     * @param eh     exception handler(s)
     * 
     * @return a new {@code HttpServer}
     * 
     * @throws NullPointerException
     *             if an argument or array element is {@code null}
     */
    HttpServer create(Config config, ExceptionHandler... eh);
}