package alpha.nomagichttp.action;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.event.RequestHeadReceived;
import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.util.TriConsumer;

/**
 * An action executed after a valid request has been received and before the
 * server attempts at resolving the request handler. The action decides whether
 * to proceed or abort the HTTP exchange and may freely populate attributes for
 * future consumption.<p>
 * 
 * Before-actions are useful to implement cross-cutting concerns such as
 * authentication, rate-limiting, collecting metrics, logging, auditing, and so
 * on.<p>
 * 
 * An action is known on other corners of the internet as a "filter", although
 * the NoMagicHTTP library has avoided this naming convention due to the fact
 * that an action has no obligation to approve, reject or modify requests. It is
 * free to do anything, including ordering pizzas online.
 * 
 * <pre>
 *   BeforeAction giveRole = (request, channel, chain) -{@literal >} {
 *       String role = myAuthLogic(request.headers());
 *       request.attributes().set("user.role", role);
 *       chain.proceed();
 *   };
 *   HttpServer server = ...
 *   // Apply to all requests
 *   server.before("/*", giveRole);
 * </pre>
 * 
 * An action writing a final response to the channel ought to also <i>abort</i>
 * the HTTP exchange, or otherwise <i>proceed</i>. Interactions (or the lack
 * thereof) with the client channel has no magical effect at all concerning what
 * happens after the action completes.
 * <pre>
 *   BeforeAction onlyAdminsAllowed = (request, channel, chain) -{@literal >} {
 *       String role = request.attributes().getAny("user.role");
 *       if ("admin".equals(role)) {
 *           chain.proceed();
 *       } else {
 *           channel.write(Responses.forbidden());
 *           chain.abort();
 *       }
 *   };
 *   HttpServer server = ...
 *   // Apply to the "admin" namespace
 *   server.before("/admin/*", onlyAdminsAllowed);
 * </pre>
 * 
 * An exception thrown from the before-action is an alternative to explicitly
 * aborting through the {@link Chain} object. The exception is handed off to the
 * {@link ErrorHandler}. This is a variant of the previous example:
 * <pre>
 *   ErrorHandler weRatherHide = (throwable, channel, ign,ored) -{@literal >} {
 *       try {
 *           throw throwable;
 *       } catch (MySuspiciousRequestException e) {
 *           var rsp = Responses.notFound().toBuilder().mustCloseAfterWrite(true).build();
 *           channel.write(rsp);
 *       }
 *   };
 *   HttpServer server = HttpServer.create(weRatherHide);
 *   BeforeAction expectAdmin = (request, channel, chain) -{@literal >} {
 *       request.attributes()
 *              .getOpt("user.role")
 *              .filter("admin"::equals)
 *              .orElseThrow(MySuspiciousRequestException::new);
 *       chain.proceed();
 *   };
 *   server.before("/admin/*", expectAdmin);
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
 * The action may be called concurrently and must be thread-safe. It is likely
 * called by the server's request thread and so must not block.<p>
 * 
 * As a word of advice; magic obfuscates and will render the architecture harder
 * to understand. Some features, although cross-cutting in nature, ought to be
 * implemented much closer to the use site, i.e. in the request handler or one
 * of its collaborators. This likely includes fault tolerance (retry, fallback,
 * ...) and transaction demarcation.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @see ActionRegistry
 */
@FunctionalInterface
public interface BeforeAction extends TriConsumer<Request, ClientChannel, Chain> {
    // Empty
}