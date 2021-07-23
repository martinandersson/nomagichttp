/**
 * Actions semantically co-exist with resources in a shared hierarchical
 * namespace and can be used to intercept requests and responses to and from
 * that namespace. Often used to implement cross-cutting concerns such as
 * authentication and response content transformations.<p>
 * 
 * A {@link alpha.nomagichttp.action.BeforeAction BeforeAction} is called after
 * the server has received a request and an {@link
 * alpha.nomagichttp.action.AfterAction AfterAction} is called after the channel
 * has received a response. The former must decide to proceed or abort the HTTP
 * exchange. The latter may return an alternative response (most likely a
 * derivative from the one who was scheduled to be written).
 */
package alpha.nomagichttp.action;