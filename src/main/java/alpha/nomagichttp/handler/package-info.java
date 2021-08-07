/**
 * Handlers make things happen.<p>
 * 
 * A {@link alpha.nomagichttp.handler.RequestHandler RequestHandler} is a
 * fundamental type in this package, it processes a {@link
 * alpha.nomagichttp.message.Request Request} into one or many {@link
 * alpha.nomagichttp.message.Response Response}s, which are written to the
 * {@link alpha.nomagichttp.handler.ClientChannel ClientChannel}. One or many
 * request handlers are added to a {@link alpha.nomagichttp.route.Route Route},
 * which is added to a {@link alpha.nomagichttp.route.RouteRegistry
 * RouteRegistry} (the {@link alpha.nomagichttp.HttpServer HttpServer}).
 */
package alpha.nomagichttp.handler;