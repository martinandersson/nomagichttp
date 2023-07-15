package alpha.nomagichttp.core;

import alpha.nomagichttp.Chain;
import alpha.nomagichttp.action.BeforeAction;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.RawRequest;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.route.Route;

import java.util.Collection;

import static alpha.nomagichttp.core.DefaultActionRegistry.Match;
import static alpha.nomagichttp.core.DefaultRequest.requestWithParams;
import static java.lang.System.Logger.Level.DEBUG;

/**
 * Is a request processing chain turning a request into a response.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class RequestProcessor
{
    private static final System.Logger LOG
            = System.getLogger(RequestProcessor.class.getPackageName());
    
    private final DefaultActionRegistry actions;
    private final DefaultRouteRegistry routes;
    
    RequestProcessor(
            DefaultActionRegistry actions,
            DefaultRouteRegistry routes) {
        this.actions = actions;
        this.routes = routes;
    }
    
    /**
     * Executes the chain.<p>
     * 
     * Exceptions propagate as-is from all co-operating entities:
     * <ul>
     *   <li>{@link DefaultActionRegistry#lookupBefore(SkeletonRequestTarget)}</li>
     *   <li>{@link BeforeAction#apply(Object, Object)}</li>
     *   <li>{@link Route#lookup(String, MediaType, Collection)}</li>
     *   <li>{@link RequestHandler#apply(Object)}</li>
     * </ul>
     * 
     * @param req the request
     * 
     * @return the final response as produced by the call chain
     *         (possibly {@code null})
     * 
     * @throws Exception see JavaDoc
     */
    Response execute(SkeletonRequest req) throws Exception {
        var matches = actions.lookupBefore(req.target());
        return matches.isEmpty() ? 
                invokeRequestHandler(req) :
                new ChainImpl(matches, req).ignite();
    }
    
    private class ChainImpl extends AbstractChain<Match<BeforeAction>> {
        private final SkeletonRequest req;
        
        ChainImpl(Collection<Match<BeforeAction>> matches,
                  SkeletonRequest req) {
            super(matches);
            this.req = req;
        }
        
        @Override
        Response callIntermittentHandler(
                     Match<BeforeAction> entity, Chain passMeThrough)
                 throws Exception {
            var req = requestWithParams(this.req, entity.segments());
            var act = entity.action();
            return act.apply(req, passMeThrough);
        }
        
        @Override
        Response callFinalHandler() throws Exception {
            return invokeRequestHandler(req);
        }
    }
    
    private Response invokeRequestHandler(SkeletonRequest r) throws Exception {
        var route = routes.lookup(r.target());
        Request app = requestWithParams(r, route.segments());
        return findRequestHandler(r.head(), route).apply(app);
    }
    
    private static RequestHandler
            findRequestHandler(RawRequest.Head h, Route r) {
        var handler = r.lookup(
                h.line().method(),
                h.headers().contentType().orElse(null),
                h.headers().accept());
        LOG.log(DEBUG, () -> "Matched handler: " + handler);
        return handler;
    }
}