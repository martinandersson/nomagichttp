/**
 * Handlers make things happen.<p>
 * 
 * A {@link alpha.nomagichttp.handler.RequestHandler RequestHandler} is a
 * fundamental type in this package, it processes a {@link
 * alpha.nomagichttp.message.Request Request} into one or many {@link
 * alpha.nomagichttp.message.Response Response}s, which are written to the
 * {@link alpha.nomagichttp.handler.ClientChannel ClientChannel}. One or many
 * request handlers are added to a {@link alpha.nomagichttp.route.Route Route},
 * which is added to the {@link alpha.nomagichttp.HttpServer HttpServer}.<p>
 * 
 * Actions co-exist with routes in a shared hierarchical namespace and can be
 * used to intercept requests and responses to and from that namespace. Often
 * used to implement cross-cutting concerns such as security, metrics and
 * response content transformations.<p>
 * 
 * A {@link alpha.nomagichttp.handler.PostRequestAction PostRequestAction} is
 * called after the server has received a request and a {@link
 * alpha.nomagichttp.handler.PostResponseAction PostResponseAction} is
 * called after the server has received a response. The former may return
 * exceptionally in order to abort the HTTP exchange, or otherwise populate
 * {@link alpha.nomagichttp.message.Request#attributes()} for future consumption
 * (the request object itself is immutable). The latter may return an
 * alternative response (most likely derived from the one who was scheduled to
 * be written).
 */
package alpha.nomagichttp.handler;