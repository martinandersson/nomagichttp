package alpha.nomagichttp;

import alpha.nomagichttp.action.AfterAction;
import alpha.nomagichttp.action.BeforeAction;
import alpha.nomagichttp.handler.ExceptionHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.util.ScopedValues;

/// An API for proceeding the active processing chain.
/// 
/// The chain is made up of executing entities.
/// 
/// There's a commonly called "request processing chain"; starting with zero or
/// more [BeforeAction] leading up to a [RequestHandler].
/// 
/// There's also an "exception processing chain"; zero or more
/// [ExceptionHandler] leading up to the server's base exception handler.
/// 
/// The executing entity can short-circuit the rest of the chain by _not_
/// calling [#proceed()], but in this case, the entity must ensure to either
/// write a final response directly on the client channel and return `null` to
/// the server, or more idiomatically just return the response.
/// 
/// @apiNote
/// [AfterAction] can not short-circuit subsequent after-actions; it is not
/// given a chain object. Whatever response an after-action returns is simply
/// passed forward by the server to the next after-action.
/// 
/// @see ScopedValues#channel()
/// @author Martin Andersson (webmaster at martinandersson.com)
@FunctionalInterface
public interface Chain
{
    /// Calls the next entity in the processing chain.
    /// 
    /// @return the response returned from the next entity
    /// 
    /// @throws UnsupportedOperationException
    ///             if not called from within the processing chain, or
    ///             if called more than once (by the same executing entity)
    /// @throws Exception
    ///             as propagated from the rest of the chain
    /// 
    /// @see Chain
    Response proceed() throws Exception;
}
