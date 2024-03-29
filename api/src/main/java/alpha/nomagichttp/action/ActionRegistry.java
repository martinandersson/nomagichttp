package alpha.nomagichttp.action;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.route.RouteRegistry;

/**
 * Provides thread-safe store operations of actions.<p>
 * 
 * As with {@link Route} and it's accompanying {@link RouteRegistry}, this
 * registry also accepts a pattern which translates to a position in a
 * hierarchical tree where the action is stored. An action may as well as a
 * route declare static segments, single-segment path parameters and catch-all
 * path parameters.<p>
 * 
 * The purpose of the action registry is also semi-equivalent to that of the
 * route registry; map an inbound request-target to decorative actions. The key
 * difference between this registry and the route registry is that
 * <i>multiple</i> actions can be stored at the same position, as long as they
 * are not equal objects as determined by {@code Object.equals(Object)} (an
 * action can be spread out at different positions).<p>
 * 
 * Further, whereas the route registry enforces constructs such as positional
 * mutual exclusivity for non-static segment types, this registry does not. A
 * request must match against only one route with zero ambiguity, but a request
 * (and/or the following response(s)) may trigger a whole plethora of actions.
 * For example, the same route registry would never accept both {@code "/*path"}
 * (all requests) and {@code "/admin"} (a subset of all) at the same time, but
 * this kind of overlapping usage is more or less the expected case for actions.
 * The normal convention will probably be to drop optional parameter names, e.g.
 * do just {@code "/*"}.<p>
 * 
 * Matching actions against the request path works in exactly the same manner as
 * it does for the route registry. A few examples:
 * 
 * <pre>
 *   Action registered: /user
 *   (exactly one segment with value specified)
 *   
 *   Request path:
 *   /user                match
 *   /                    no match
 *   /foo                 no match
 *   /user/foo            no match
 * </pre>
 * 
 * <pre>
 *   Action registered: /:
 *   (exactly one segment with any value)
 *   
 *   Request path:
 *   /user                match
 *   /foo                 match
 *   /                    no match
 *   /user/foo            no match
 *   /foo/user            no match
 * </pre>
 * 
 * <pre>
 *   Action registered: /user/*
 *   (first segment specified, followed by anything)
 *   
 *   Request path:
 *   /user                match
 *   /user/foo            match
 *   /user/foo/bar        match
 *   /foo                 no match
 * </pre>
 * 
 * It is common for action implementations to have a dependency on other actions
 * executing first (or later), and so, the invocation order of more than one
 * matched action is well-defined.<p>
 * 
 * If multiple <i>before-actions</i> are matched, then they will be invoked
 * primarily in the order that segments are discovered (discovery starts at the
 * root and walks a branch of the tree one segment at a time), secondarily by
 * their implicit unspecificity (catch-all first, then single-segment path, then
 * static segment) and thirdly by their registration order. The rule is to match
 * from the most broad action first to the most niche action last, but still
 * maintain the registration order provided by the application.<p>
 * 
 * For example:
 * <pre>
 *   Request path: /
 *   
 *   Before-action execution order:
 *   /*
 *   /
 * </pre>
 * 
 * <pre>
 *   Request path: /foo/bar
 *   
 *   Before-action execution order:
 *   /*
 *   /:/bar
 *   /foo/*
 *   /foo/:
 *   /foo/bar (added first)
 *   /foo/bar (added last)
 * </pre>
 * 
 * The invocation order of <i>after-actions</i> works sort of the same way, but
 * in reverse. The rule is to match the most niche action first, followed by
 * more generic ones, and as before, also honor the application's insertion
 * order.
 * 
 * <pre>
 *   Request path: /
 *   
 *   After-action execution order:
 *   /
 *   /*
 * </pre>
 * 
 * <pre>
 *   Request path: /foo/bar
 * 
 *   After-action execution order:
 *   /foo/bar (added first)
 *   /foo/bar (added last)
 *   /foo/:
 *   /foo/*
 *   /:/bar
 *   /*
 * </pre>
 * 
 * A combined high-level example of HTTP exchanges decorated by actions:
 * <pre>
 *   Before action:
 *   /*             1) save request correlation id from header or perhaps generate new
 *   /admin/*           2) authenticate, give role, authorize
 *   
 *   After action:
 *   /admin/*path       3) if response is 403 (Forbidden), log warning with path
 *   /*             4) copy correlation id to response header
 * </pre>
 * 
 * For clarity in code, actions should be added to the registry in the order of
 * invocation.<p>
 * 
 * {@snippet :
 *   HttpServer.create(myGlobalExceptionHandlers)
 *             .before(myAllRequestsAction)
 *                 .before(mySpecificPathAction)
 *                 .add(mySpecificPathResource)
 *                 .after(mySpecificPathAction)
 *             .after(myAllResponsesAction)
 *             ...
 * }
 * 
 * An action added to the registry is not necessarily <i>immediately</i> visible
 * to currently active HTTP exchanges and should generally be registered before
 * the server starts. Before-actions are matched only once after a valid request
 * head was received; at the beginning of each HTTP exchange. After-actions are
 * matched only once after the first response was received and accepted by the
 * channel.<p>
 * 
 * Unlike {@link RouteRegistry}, this interface does not declare remove
 * operations. The reasons behind this decision was to reduce the API footprint
 * as well as to enable certain performance optimizations in the implementation.
 * Nor is this expected to hinder the demands of most applications. Routes may
 * come and go at runtime and serve specific purposes only for a limited amount
 * of time. Actions on the other hand represents cross-cutting concerns. E.g.
 * security and metrics are likely setup once during initialization with no need
 * to ever turn them off again. This could of course be implemented in the
 * action itself by using a feature flag or whatever.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public interface ActionRegistry {
     /**
     * Registers a before-action.
     * 
     * @param pattern of action(s)
     * @param first   action
     * @param more    optionally more actions
     * 
     * @return the HttpServer instance (for chaining/fluency)
     * 
     * @throws NullPointerException
     *             if any argument is {@code null}
     * @throws ActionPatternInvalidException
     *             if a static segment value is empty, or
     *             if a catch-all segment is not the last one
     * @throws ActionNonUniqueException
     *             if an equal action has already been added to the same position
      *            (object equality)
     */
    HttpServer before(String pattern, BeforeAction first, BeforeAction... more);
    
    /**
     * Registers an after-action.
     * 
     * @param pattern of action(s)
     * @param first   action
     * @param more    optionally more actions
     * 
     * @return the HttpServer instance (for chaining/fluency)
     * 
     * @throws NullPointerException
     *             if any argument is {@code null}
     * @throws ActionPatternInvalidException
     *             if a static segment value is empty, or
     *             if a catch-all segment is not the last one
     * @throws ActionNonUniqueException
     *             if an equal action has already been added to the same position
     *             (object equality)
     */
    HttpServer after(String pattern, AfterAction first, AfterAction... more);
}