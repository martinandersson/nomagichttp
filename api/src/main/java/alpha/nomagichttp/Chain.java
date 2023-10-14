package alpha.nomagichttp;

import alpha.nomagichttp.action.AfterAction;
import alpha.nomagichttp.action.BeforeAction;
import alpha.nomagichttp.handler.ExceptionHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.util.ScopedValues;

/**
 * An API for proceeding the active processing chain.<p>
 * 
 * The chain is made up of executing entities. These entities are
 * {@link BeforeAction}s leading up to a {@link RequestHandler} (aka. the
 * "request processing chain"), and exceptionally; any number of
 * application-provided {@link ExceptionHandler}s, leading up to the server's
 * base exception handler (aka. "the exception processing chain").<p>
 * 
 * The chain should return a final response. A before-action will normally not
 * produce a response and instead yield control to the rest of the chain. An
 * exception handler will normally return a final response directly if it can
 * handle the exception, otherwise yield control if it can not.<p>
 * 
 * An intermittent handler in the request processing chain should normally not
 * manipulate the response returned from the rest of the chain. Response
 * manipulation is the purpose of {@link AfterAction}s.<p>
 * 
 * The chain may return {@code null} if and only if it has already written a
 * final response to the {@link ScopedValues#channel() channel}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
@FunctionalInterface
public interface Chain
{
    /**
     * Calls the next entity in the processing chain.
     * 
     * @return the response returned from the next entity
     * 
     * @throws UnsupportedOperationException
     *             if not called from within the processing chain, or
     *             if called more than once (by the executing entity)
     * @throws Exception
     *             as propagated from the rest of the chain
     * 
     * @see Chain
     */
    Response proceed() throws Exception;
}