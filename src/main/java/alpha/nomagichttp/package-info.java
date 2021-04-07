/**
 * Home of the {@code HttpServer}.<p>
 * 
 * <strong>Architectural Overview</strong>. The {@link
 * alpha.nomagichttp.HttpServer HttpServer} is essentially a collection of
 * resources, aka {@link alpha.nomagichttp.route.Route Route}s. A route has at
 * least one {@link alpha.nomagichttp.handler.RequestHandler RequestHandler}
 * that knows how to process a {@link alpha.nomagichttp.message.Request Request}
 * into a {@link alpha.nomagichttp.message.Response Response}.<p>
 * 
 * <strong>Examples</strong>. See package {@link alpha.nomagichttp.examples}.
 */
package alpha.nomagichttp;