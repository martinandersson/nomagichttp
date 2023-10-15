import alpha.nomagichttp.HttpServerFactory;

/**
 * Now this has a comment.
 */
module alpha.nomagichttp {
    uses HttpServerFactory;
    
    exports alpha.nomagichttp;
    exports alpha.nomagichttp.action;
    exports alpha.nomagichttp.event;
    exports alpha.nomagichttp.handler;
    exports alpha.nomagichttp.message;
    exports alpha.nomagichttp.route;
    exports alpha.nomagichttp.util;
}
