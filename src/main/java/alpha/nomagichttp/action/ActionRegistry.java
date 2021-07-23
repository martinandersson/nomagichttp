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
 * path parameters. The purpose of the implementation is also equivalent to that
 * of the route registry; map an inbound request-target to decorative
 * actions.<p>
 * 
 * The key difference between this registry and the route registry is that
 * <i>multiple</i> actions can be stored at the same position, as long as they
 * are not equal objects. If many actions are matched for a request, then they
 * will be called in the order they are discovered and secondarily in the order
 * they were added (discovery starts at the root and walks down a branch of the
 * tree one segment at a time).<p>
 * 
 * In the following examples, please note that path parameters can be
 * named/keyed with an empty string. The only requirement is that the parameter
 * names are unique for that action (this is no different from {@code Route}).
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
 * Actions are not mutually exclusive and a catch-all parameter declared by an
 * action does not end that hierarchy. For instance, route "/user/avatar" and
 * "/user/*filepath" can not be added to the same route registry; they would've
 * collided. But actions "/user/avatar" and "/user/*filepath" can be added to
 * the same action registry. The former would be called only if the request
 * path's second segment has the value "avatar", the latter would always be
 * called no matter what follows the first segment. Similarly, route "/foo/*bar"
 * and "/foo/bla/bla/bar" can not be added to the same registry. But both of
 * these patterns can co-exist in the action registry.<p>
 * 
 * The request object is pseudo-immutable (has no setters) and the instance
 * remains the same throughout the HTTP exchange. This is great as attributes
 * propagates across executional boundaries. But it also means that
 * before-actions should not consume request body bytes unless it is known that
 * the request handler won't (there is currently no API support to cache the
 * bytes).<p>
 * 
 * The {@link Parameters} object returned from {@link Request#parameters()} may
 * be unique for each entity invoked or otherwise act as an entity-unique view
 * and supports solely retrieving path parameters by the key with which they
 * were registered. For example, although request "/hello" matches before-action
 * "/:foo" and request handler "/:bar", the former will have to use the key
 * "foo" and the latter will have to use the key "bar" when retrieving the
 * segment value. "bar" will return {@code null} for the action and "foo" will
 * return {@code null} for the request handler.<p>
 * 
 * Unlike {@link RouteRegistry}, this interface does not declare remove
 * operations. It is conceivable that routes may come and go at runtime and
 * serve specific purposes only for a limited amount of time. Actions on the
 * other hand represents cross-cutting concerns. E.g. security and metrics are
 * likely setup once during initialization with no need to ever turn them off
 * again. This could of course be implemented in the action through a feature
 * flag of sorts.
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