package alpha.nomagichttp.action;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Request.Parameters;
import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.route.RouteRegistry;

/**
 * Provides thread-safe store operations of actions.<p>
 * 
 * As with {@link Route} and it's accompanying {@link RouteRegistry}, this
 * registry too accepts a pattern which translates to a position in a
 * hierarchical tree where the action is stored. An action may as well as a
 * route declare static segments, single-segment path parameters and catch-all
 * path parameters.<p>
 * 
 * The purpose of the implementation is also semi-equivalent to that of the
 * route registry; map an inbound request-target to decorative actions. The key
 * difference between this registry and the route registry is that
 * <i>multiple</i> actions can be stored at the same position, as long as they
 * are not equal objects. Further, whereas the route registry enforces
 * constructs such as mutual exclusivity in tree nodes for non-static segment
 * types, this registry does not. A request must match against only one route
 * with zero ambiguity, but a request (and/or the following response(s)) may
 * trigger a whole plethora of actions. For example, the route registry would
 * never accept {@code "/*path"} (all requests) and {@code "/admin"} (a subset
 * of requests) at the same time, but this is more or less the expected case for
 * the action registry. Except the normal convention will probably be to drop
 * optional parameter names, e.g. {@code "/*"}.<p>
 * 
 * If many before-actions are matched, then they will be called in the order
 * they are discovered and secondarily in the order they were added (discovery
 * starts at the root and walks down a branch of the tree one segment at a
 * time). This is quite important as it's expected to be common that action
 * implementations have an order dependency on other actions.<p>
 * 
 * The invocation order of after-actions works must the same, with one key
 * difference. The primary order based on discovery is <i>reversed</i>. The
 * secondary order based on insertion remains the same. I.e. before-action
 * {@code /*} is called before {@code /admin}. After-action {@code /admin} is
 * called before {@code /*}.
 * 
 * <pre>
 *   Action registered: /user
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
 *   
 *   Request path:
 *   /user                match
 *   /user/foo            match
 *   /user/foo/bar        match
 *   /foo                 no match
 * </pre>
 * 
 * The request object is pseudo-immutable (has no setters) and the instance
 * remains the same throughout the HTTP exchange. This is great as attributes
 * propagates across executional boundaries. But it also means that
 * before-actions should not consume request body bytes unless it is known that
 * the request handler won't (there is currently no API support to cache the
 * bytes).<p>
 * 
 * The {@link Parameters} object returned from {@link Request#parameters()} may
 * be unique for each executable entity invoked or otherwise act as an
 * entity-unique view and supports solely retrieving path parameters if and only
 * if they were declared using the the key by which they were declared. For
 * example, although request "/hello" matches before-action "/:foo" and request
 * handler "/:bar", the former will have to use the key "foo" and the latter
 * will have to use the key "bar" when retrieving the segment value.<p>
 * 
 * Unlike {@link RouteRegistry}, this interface does not declare remove
 * operations. The chief reasons behind this decision was to reduce the API
 * footprint as well as to enable certain performance optimizations in the
 * implementation. It is further expected not to hinder the demands of most
 * applications. Routes may come and go at runtime and serve specific purposes
 * only for a limited amount of time. Actions on the other hand represents
 * cross-cutting concerns. E.g. security and metrics are likely setup once
 * during initialization with no need to ever turn them off again. This could of
 * course be implemented in the action itself through a feature flag of sorts.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public interface ActionRegistry {
     /**
     * Register a before action.
     * 
     * @param pattern of action(s)
     * @param first   action
     * @param more    optionally more actions
     * 
     * @return the HttpServer (for chaining/fluency)
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
     * Register an after action.
     * 
     * @param pattern of action(s)
     * @param first   action
     * @param more    optionally more actions
     * 
     * @return the HttpServer (for chaining/fluency)
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