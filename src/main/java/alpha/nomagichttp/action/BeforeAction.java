package alpha.nomagichttp.action;

import alpha.nomagichttp.Chain;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.event.RequestHeadReceived;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.util.Throwing;
import jdk.incubator.concurrent.ScopedValue;

/**
 * Is an action executed before the request handler.<p>
 * 
 * The action is executed between a valid request head has been received and
 * the server attempts at resolving the request handler. The action may decide
 * to proceed the request processing chain or return a response directly.<p>
 * 
 * Before-actions are useful to implement cross-cutting concerns such as
 * authentication, rate-limiting, collecting metrics, fault tolerance (retry,
 * circuit breaker ...), and so on.<p>
 * 
 * Although a before-action can be used to handle exceptions — and for well
 * defined route patterns this is certainly an option — the {@link ErrorHandler}
 * is what should be used for global errors.<p>
 * 
 * <pre>
 *   BeforeAction giveRole = (request, chain) -{@literal >} {
 *       String role = myAuthLogic(request.headers());
 *       request.attributes().set("user.role", role);
 *       return chain.proceed();
 *   };
 *   HttpServer server = ...
 *   // Apply to all requests
 *   server.before("/*", giveRole);
 * </pre>
 * 
 * If the value stored in the attributes acts as a default and will need to be
 * temporarily rebound at a later point in time, consider using a
 * {@link ScopedValue} instead.<p>
 * 
 * TODO: Give example.<p>
 * 
 * Word of caution: Error handlers are called from outside of the request
 * processing chain. For the previous example, this means that error handlers
 * will not be able to observe the bound value. They will, however, be able to
 * read request attributes (assuming the request exists at that point in
 * time).<p>
 * 
 * An action is known on other corners of the internet as a "filter", although
 * the NoMagicHTTP library has avoided this naming convention due to the fact
 * that an action has no obligation to approve, reject or modify requests. It is
 * free to do anything, including ordering pizza online.<p>
 * 
 * In particular, a before-action has no obligation to proceed the request
 * processing chain.
 * 
 * <pre>
 *   BeforeAction onlyAdminsAllowed = (request, chain) -{@literal >} {
 *       String role = request.attributes().getAny("user.role");
 *       if (!"admin".equals(role)) {
 *           // Short-circuit the rest of the processing chain
 *           return Responses.forbidden();
 *       }
 *       return chain.proceed();
 *   };
 *   HttpServer server = ...
 *   // Apply to the "admin" namespace
 *   server.before("/admin/*", onlyAdminsAllowed);
 * </pre>
 * 
 * An exception thrown from the before-action will be handed off to the error
 * handlers. This is a variant of the previous example:
 * <pre>
 *   ErrorHandler hideResource = (exc, chain, request) -{@literal >} {
 *       if (exc instanceof MySuspiciousRequestException) {
 *           // Or set response header "Connection: close" (equivalent)
 *           channel().shutdownInput();
 *           return Responses.notFound();
 *       }
 *       return chain.proceed();
 *   };
 *   HttpServer server = HttpServer.create(hideResource);
 *   BeforeAction requireAdmin = (request, chain) -{@literal >} {
 *       request.attributes()
 *              .getOpt("user.role")
 *              .filter("admin"::equals)
 *              .orElseThrow(MySuspiciousRequestException::new);
 *       return chain.proceed();
 *   };
 *   server.before("/admin/*", requireAdmin);
 * </pre>
 * 
 * The action is called only after a request head has been received and
 * validated. For example, the action is not called if a request head fails to
 * be parsed or the HTTP version was rejected/unsupported.<p>
 * 
 * An action that will be invoked for all <i>valid</i> requests hitting the
 * server can be registered using the path "/*". If the purpose for such an
 * action is to gather metrics, consider instead tapping into all request heads
 * received regardless if the request is valid, by subscribing to the {@link
 * RequestHeadReceived} event (see {@link HttpServer#events()}).<p>
 * 
 * The action is called before the request handler resolution begins. This means
 * that the action is called even if a particular route turns out to not exist
 * or the route exists but has no applicable request handler.<p>
 * 
 * The action may be called concurrently and must be thread-safe.<p>
 * 
 * No argument passed to the action will be {@code null}.<p>
 * 
 * As a word of advice; magic obfuscates and will render the architecture harder
 * to understand. Some features, although cross-cutting in nature, ought to be
 * implemented much closer to where it is applied, that is to say, in the
 * request handler or in one of its collaborators. This likely includes
 * transaction demarcation.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see ActionRegistry
 */
@FunctionalInterface
public interface BeforeAction
       extends Throwing.BiFunction<Request, Chain, Response, Exception> {
    // Empty
}