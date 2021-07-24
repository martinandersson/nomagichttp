package alpha.nomagichttp.action;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.events.RequestHeadParsed;
import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.message.Request;

/**
 * An action executed after a valid request has been received and <i>before</i>
 * the server attempts at resolving the request handler. The action decides
 * whether to proceed or abort the HTTP exchange and may freely populate
 * attributes for future consumption.<p>
 * 
 * Before-actions are useful to implement cross-cutting concerns on a
 * request-level such as authentication, rate-limiting, collecting metrics,
 * logging, auditing, and so on.<p>
 * 
 * An action is known on other corners of the internet as a "filter", although
 * the NoMagicHTTP library has avoided this naming convention due to the fact
 * that an action has no obligation to approve, reject or modify requests. It is
 * free to do anything, including ordering pizzas online.
 * 
 * <pre>
 *   BeforeAction giveRole = (request, channel, chain) -{@literal >} {
 *       String role = authenticate(request.headers());
 *       request.attributes().set("user.role", role);
 *       chain.proceed();
 *   };
 *   HttpServer server = ...
 *   server.before("/*", giveRole);
 * </pre>
 * 
 * An action writing a final response to the channel ought to also abort the
 * HTTP exchange. Interactions with the client channel has no magical effect at
 * all concerning what happens after the action completes.
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
 *   server.before("/admin/*", onlyAdminsAllowed);
 * </pre>
 * 
 * Exceptions thrown from the before action is given to the {@link
 * ErrorHandler}. This is a variation of the previous example:
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
 * The action is called after a request head has been received and validated,
 * but before the request handler resolution even begins. This means that the
 * action is called even if a particular route turns out to not exist or the
 * route exists but has no applicable request handler.<p>
 * 
 * An action that will be invoked for all valid requests hitting the server can
 * be registered using the path "/*". If the purpose for such an action is to
 * gather metrics, consider tapping into all requests instead - whether or not
 * they are valid - by subscribing to the {@link RequestHeadParsed} event (see
 * {@link HttpServer#events()}).<p>
 * 
 * The action may be called concurrently and must be thread-safe. It is called
 * by the server's request thread and so must not block.<p>
 * 
 * As a word of advice; magic obfuscates and will render the architecture harder
 * to understand. Some features, although cross-cutting in nature, ought to be
 * implemented much closer to the use site, i.e. in the request handler or one
 * of its collaborators. This likely includes fault tolerance (retry, fallback,
 * ...) and transaction demarcation.
 * 
 * @see alpha.nomagichttp.action package-info
 */
@FunctionalInterface
public interface BeforeAction {
    /**
     * Apply the action.
     * 
     * @param request received (never {@code null})
     * @param channel to client (never {@code null})
     * @param chain completion mechanism (never {@code null})
     * 
     * @see BeforeAction
     */
    void apply(Request request, ClientChannel channel, Chain chain);
}