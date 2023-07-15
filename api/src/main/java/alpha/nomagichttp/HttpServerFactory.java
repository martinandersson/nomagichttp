package alpha.nomagichttp;

import alpha.nomagichttp.handler.ErrorHandler;

/**
 * Factory of HttpServer.
 */
@FunctionalInterface
public interface HttpServerFactory {
    /**
     * Creates the server.
     * 
     * @param config configuration
     * @param eh error handlers
     * @return the server
     */
    HttpServer create(Config config, ErrorHandler... eh);
}