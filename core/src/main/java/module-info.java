import alpha.nomagichttp.HttpServerFactory;
import alpha.nomagichttp.core.DefaultServerFactory;

/**
 * The core module lol
 */
module alpha.nomagichttp.core {
    requires alpha.nomagichttp;
    
    provides HttpServerFactory with DefaultServerFactory;
}
