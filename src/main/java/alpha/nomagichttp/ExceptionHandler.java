package alpha.nomagichttp;

import alpha.nomagichttp.message.Response;

import java.util.concurrent.CompletionStage;

/**
 * WARNING: Exception handling is completely untested, not fully defined and
 * basically not implemented - well, currently.<p>
 * 
 * The idea, however, is something like this: introduce exception handlers that
 * can be plugged in to the server.<p>
 * 
 * Firstly, documentation should lightly discourage using the exception-handling
 * API to deal with <i>application errors</i> as this is effectively hiding away
 * a layer of application logic inside the server (i.e. magic). Always first try
 * a different approach!<p>
 * 
 * But, if there was ever a good reason to introduce a layered architecture then
 * exception handling is probably a very good cause. For example, an application
 * may have an internal Conversion API which translates type A into type B. This
 * conversion service should have no dependencies on HTTP message types Request
 * and/or Response and so can not on its own customize an error-response to the
 * client. Nor would it be very clean to copy-paste the same try-catch block for
 * each and every service call site in the application. Consequently, an error
 * handler higher up in a layered architecture which can respond to known
 * exception types is a good solution.<p>
 * 
 * An exception handler is also the <i>only</i> solution for the application to
 * plug-in to the server in order to handle <i>server errors</i>. As always,
 * we do wish to grant the application developer "as much control as he wishes"
 * over the HTTP exchange. For example, if the server fails to parse a valid
 * request from the client, then what should be the response?<p>
 * 
 * Other frameworks offer the application developer an extremely limited array
 * of capabilities to deal with errors, capabilities which more likely than not
 * will be centered around the not-so-humble idea that only the handler - i.e.,
 * application code can fail - not the server itself, pre-request and/or
 * post-response. In these frameworks, error handling is often provided through
 * a bloat of hard-to-understand annotations with limited value. By the end of
 * the day, the application might only be able to perform the most basic actions
 * such as swapping out a default HTTP status code for another. Sad!<p>
 * 
 * We should offer two exception handler types of which an instance can be
 * inserted either on a server-level (one per server) or on a route-level (one
 * per route). This infrastructure will provide an exceptional ability for the
 * developer to tap into the server's error handling for a customized
 * client-response.<p>
 * 
 * Coupled together with clear documentation pointing out default types and the
 * behavior of those types, this will also grant the developer something else
 * that is largely missing from most other "happy path" frameworks; the ability
 * to learn and discover exactly how the server deals with errors. No more
 * undocumented surprises or testing in production!<p>
 * 
 * 
 * <h3>Route-level exception handling</h3>
 * 
 * Any error happening after the point in time when an inbound request has been
 * matched against a route up to the last moment just before the response begins
 * writing back to the client (this includes errors signalled to the body
 * subscriber before the first bytebuffer has been published), is handled by the
 * exception handler on the route-level.<p>
 * 
 * This exception handler will therefore most likely capture only errors that is
 * originating from an application-provided request handler (must be specified
 * of course!).<p>
 * 
 * The route-level exception handler will naturally have access to many
 * arguments such as Route, Request and possibly a request Handler. The
 * exception handler returns a Response, which will replace the response
 * originally intended but never produced.<p>
 * 
 * At this level, the exception handler can simply re-invoke the same request
 * handler for a new attempt at generating a response, or do whatever else he
 * feels is necessary.<p>
 * 
 * The route-level exception handler is expected to work with "retries" or apply
 * other types of patterns that are dependent on the invocation context. Hence,
 * the type provided to NoMagicHTTP should be a {@code
 * Function<? super Exception, ? extends RouteExceptionHandler>} (for example
 * "MyRouteExceptionHandler::new"). The library will call this factory for each
 * new error and re-apply the same error handler for subsequent errors happening
 * in the same call chain. This way, the route exception handler will receive
 * the original cause in his constructor which he can "suppress" in the call to
 * the exception handler method, where he do other types of maintenance such as
 * perhaps keeping a call count.<p>
 * 
 * If a route-level exception handler is not set, the exception will be sent to
 * the server-level exception handler.
 * 
 * 
 * <h3>Server-level exception handling</h3>
 * 
 * The server-level handler deals with errors occurring after a client-request
 * was initiated but before the route has been matched. This is where
 * application code can tap into the "pre-handler stage" (i.e. initial request
 * head parsing) and as before, return an alternative response. As with the
 * route-level, this window needs to be exactly specified.<p>
 * 
 * The server-level exception handler is not dependent on handler invocation
 * context - so we don't need a factory - and, the handler signature will
 * receive far less items, perhaps even none except the exception itself.<p>
 * 
 * If a server-level exception handler is not set, a default will be provided.
 * The default handler will log the exception, then lookup and use an
 * exception-specific response. For unknown exception types it will respond a
 * "500 Internal Server Error". After response, the default handler will
 * initiate an orderly shutdown procedure, same as the "global error
 * handler" described in the next section.<p>
 * 
 * Note 1: Ideally, we want to log the exception the last thing we do, because
 * if the default handler itself throws an exception, it will propagate to the
 * global error handler (propagation is discussed later) which will log the
 * exception (indirectly as the suppressed). So there's simply no need to log
 * the same exception twice.<p>
 * 
 * Note 2: The application-provided server-level exception handler
 * <i>replaces</i> the default. If the application-provided handler only wish to
 * replace a part of the default behavior or simply wants to fallback on the
 * default, then the default handler must be explicitly called.<p>
 * 
 * 
 * <h3>Global error handler</h3>
 * 
 * Exceptions occurring outside the previously mentioned windows can not be
 * customized. Firstly, because there's simply no HTTP exchange taking place, so
 * really nothing special to do about it. Secondly, because these errors are not
 * expected to happen and so when they do; almost certainly indicates a bug in
 * the server implementation which needs to be properly addressed and not
 * "handled".<p>
 * 
 * Nonetheless, there is a global error handler defined which must be mentioned
 * in the server implementation type's JavaDoc (for purpose of traceability and
 * transparency). This global error handler is probably nothing other than a
 * utility function of some sort that first logs the exception, then performs an
 * orderly shutdown procedure.<p>
 * 
 * The shutdown procedure will first attempt to close the child channel. If
 * child channel does not exists or otherwise fails to be closed, the function
 * will attempt to stop the server instance. If the server instance can not be
 * stopped, the application/JVN will exit.<p>
 * 
 * The idea behind the shutdown procedure is to reduce the risk of leaking
 * resources as well as reducing the risk of breached security; we'd rather be
 * "safe than sorry".<p>
 * 
 * Server-code outside of the windows defined by the route- and server-level
 * sections, must ensure that all exceptions are caught and delivered to the
 * global error handler, then afterwards, the exception may be re-thrown and
 * made visible to whatever thread is executing at that time (given the JVM
 * didn't exit of course).<p>
 * 
 * If the server-code does not catch-all as previously described, a thread pool
 * could swallow the exception making not only the application developer unaware
 * of the problem, but also bypass the security which the global error handler
 * is meant to provide.<p>
 * 
 * 
 * <h3>Other considerations</h3>
 * 
 * The shutdown-procedure should also be accessible to application-provided
 * exception handlers. Probably the easiest and most straight forward way is to
 * introduce a "Response.thenClose()" method, therefore also accessible to all
 * response-generating handlers no matter their type.<p>
 * 
 * Exceptions from an exception handler (whether new or re-thrown) propagates.
 * A <i>new</i> exception will mark the "old" as suppressed. Exceptions from the
 * exception handler on route-level will go to the exception handler on the
 * server-level and finally go to the global error handler.<p>
 * 
 * Before invoking a route- and server exception handler, we must consider
 * unboxing a {@code CompletionException} if and only if the error originates
 * from server-code and not an application-provided request handler. I.e, the
 * exception handler logic should not have to know how exactly tasks are
 * executed by the library. For example, an exception handler should be able to
 * type-check for a {@code BadRequestException} without having to dig through
 * the hierarchy of caused-by exceptions. On handler re-throw, the handler
 * invocation logic should probably be smart enough to propagate the original
 * {@code CompletionException} down the line so that eventually, a full and
 * complete stacktrace is logged by the global error handler. All this should
 * be documented of course, so that the application developer understands that
 * only logging the exception in a custom exception handler might not reveal the
 * complete stacktrace and could end up having less utility value than to simply
 * let the library defaults kick-in.<p>
 * 
 * Different exception types in the library must be documented and specified
 * where exactly - in what "window" - will they occur. I.e. give the application
 * an easy and straight forward way to handle these exceptions for a
 * "different-than-default" response. He should never have to "guess". That's
 * what other frameworks are for.<p>
 * 
 * Exception handlers should receive Throwable and documentation should clarify
 * that the server - before passing or before handling exceptions himself - does
 * not discriminate between exception types. E.g., the handler could receive
 * really nasty stuff like OutOfMemoryError or StackOverflowError. But then,
 * this shouldn't be a concern and is no different from "normal exception
 * handling"; if handler doesn't recognize a particular exception and can not
 * produce a response, then its only way out is to re-throw the exception back
 * to the server (the JVM could recover from OOME, and if it doesn't, well then
 * we're pretty fucked anyways aren't we).
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
@FunctionalInterface
public interface ExceptionHandler
{
    CompletionStage<Response> apply(Throwable exc);
    
    ExceptionHandler DEFAULT = exc -> {
        // TODO: Immediately rethrow Error?
        // HTTP server parse exception translates to 400 (Bad Request).
        // Max size over exceeded translates to 413 Entity Too Large.
        return null;
    };
}