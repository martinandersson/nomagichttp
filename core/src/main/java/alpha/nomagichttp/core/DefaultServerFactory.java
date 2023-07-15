package alpha.nomagichttp.core;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.HttpServerFactory;
import alpha.nomagichttp.handler.ErrorHandler;

/**
 * Default {@code HttpServerFactory}.<p>
 * 
 * When loading a service provider from a module, the provider can be a static
 * method. E.g. {@code DefaultServer.provider()}.<p>
 * 
 * And so, it may seem like this class is redundant. This class exists, and is
 * specified in the provider configuration file, because this author could not
 * get a legacy, non-modular Java app to load the provider using a provider
 * method. The configuration name is specified to be a fully qualified name, and
 * a method does not have a fully qualified name.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
// TODO: Replace with DefaultServer.provider() when configuration file adds support
public class DefaultServerFactory implements HttpServerFactory
{
    /**
     * Constructs this object.
     */
    public DefaultServerFactory() {
        // Empty
    }
    
    @Override
    public HttpServer create(Config config, ErrorHandler... eh) {
        return new DefaultServer(config, eh);
    }
}
