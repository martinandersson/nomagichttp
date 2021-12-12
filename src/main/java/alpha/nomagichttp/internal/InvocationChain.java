package alpha.nomagichttp.internal;

import alpha.nomagichttp.action.BeforeAction;
import alpha.nomagichttp.action.Chain;
import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.RequestHead;
import alpha.nomagichttp.route.NoRouteFoundException;
import alpha.nomagichttp.route.Route;

import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static alpha.nomagichttp.HttpConstants.Version;
import static alpha.nomagichttp.internal.DefaultActionRegistry.Match;
import static alpha.nomagichttp.internal.HttpExchange.RequestCreated;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;
import static java.util.concurrent.CompletableFuture.completedStage;

/**
 * Represents a work flow of finding and executing before-actions and a request
 * handler. These entities will co-operate to write responses to a channel.<p>
 * 
 * The entry point is {@link #execute(SkeletonRequest, Version)}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class InvocationChain extends AbstractLocalEventEmitter
{
    /** Sentinel reference meaning the chain was aborted by a before-action. */
    static final Throwable ABORTED = new Throwable();
    
    private static final System.Logger LOG
            = System.getLogger(InvocationChain.class.getPackageName());
    
    private static final CompletionStage<Void> COMPLETED = completedStage(null);
    
    private final DefaultActionRegistry actions;
    private final DefaultRouteRegistry routes;
    private final ClientChannel chApi;
    
    InvocationChain(DefaultActionRegistry actions, DefaultRouteRegistry routes, ClientChannel chApi) {
        assert actions != null;
        assert routes  != null;
        assert chApi   != null;
        this.actions = actions;
        this.routes  = routes;
        this.chApi   = chApi;
    }
    
    /**
     * First look up and execute before-actions, then look up and execute
     * a request handler.<p>
     * 
     * The returned stage completes normally only when the entire chain
     * completes normally.<p>
     * 
     * Otherwise, the stage completes exceptionally with any one of:
     * <ul>
     *   <li>{@link NoRouteFoundException}</li>
     *   <li>An exception thrown by one of the executed entities</li>
     *   <li>If a before-action aborts the chain, the stage will complete with a
     *      {@code CompletionException} with its cause set to the instance
     *      {@link #ABORTED}</li>
     * </ul>
     * 
     * A normal completion and a throwable where {@code getCause() == ABORTED}
     * ought to semantically have the same outcome; i.e. if the {@linkplain
     * ResponsePipeline pipeline} is still waiting for the final response then
     * none, otherwise a new HTTP exchange.
     * 
     * @param req request
     * @param ver HTTP version
     * 
     * @return see JavaDoc
     */
    CompletionStage<Void> execute(SkeletonRequest req, Version ver) {
        return invokeBeforeActions(req, ver)
                   .thenRun(() ->
                       invokeRequestHandler(req, ver));
    }
    
    private CompletionStage<Void> invokeBeforeActions(SkeletonRequest req, Version ver) {
        var matches = actions.lookupBefore(req.target());
        if (matches.isEmpty()) {
            return COMPLETED;
        }
        var allOf = new CompletableFuture<Void>();
        new ChainImpl(matches.iterator(), allOf, req, ver)
                .callAction();
        return allOf;
    }
    
    private void invokeRequestHandler(
            SkeletonRequest req, Version ver)
    {
        Route r = routes.lookup(req.target());
        var real = new DefaultRequest(ver, req, r.segments());
        InvocationChain.super.emit(RequestCreated.INSTANCE, real, null);
        findRequestHandler(req.head(), r)
            .logic().accept(real, chApi);
    }
    
    private static RequestHandler findRequestHandler(RequestHead rh, Route r) {
        RequestHandler h = r.lookup(
                rh.method(),
                rh.headers().contentType().orElse(null),
                rh.headers().accept());
        LOG.log(DEBUG, () -> "Matched handler: " + h);
        return h;
    }
    
    // Status of a before-action invocation;
    //     both implicit- and explicit completion required for continuation
    private static final int
            AWAITING_BOTH = 0,
            AWAITING_EXPL = 1,
            AWAITING_IMPL = 2,
            AWAITING_NONE = 3;
    
    private class ChainImpl implements Chain {
        private final Iterator<Match<BeforeAction>> matches;
        private final CompletableFuture<Void> allOf;
        private final AtomicInteger status;
        private final SkeletonRequest shared;
        private final Version ver;
        
        ChainImpl(
                Iterator<Match<BeforeAction>> matches,
                CompletableFuture<Void> allOf,
                SkeletonRequest shared,
                Version ver)
        {
            this.matches = matches;
            this.allOf   = allOf;
            this.status  = new AtomicInteger(AWAITING_BOTH);
            this.shared  = shared;
            this.ver     = ver;
        }
        
        void callAction() {
            try {
                var m = matches.next();
                var real = new DefaultRequest(ver, shared, m.segments());
                InvocationChain.super.emit(RequestCreated.INSTANCE, real, null);
                m.action().accept(real, chApi, this);
            } catch (Throwable t) {
                status.set(AWAITING_NONE);
                if (!allOf.completeExceptionally(t)) {
                    LOG.log(WARNING,
                        "Before-action returned exceptionally, but the chain was already aborted. " +
                        "This error is ignored.", t);
                }
                return;
            }
            if (implicitComplete()) {
                tryCallNextAction();
            }
        }
        
        private void tryCallNextAction() {
            if (!matches.hasNext()) {
                allOf.complete(null);
            } else {
                new ChainImpl(matches, allOf, shared, ver)
                        .callAction();
            }
        }
        
        @Override
        public void proceed() {
            if (explicitComplete()) {
                tryCallNextAction();
            }
        }
        
        @Override
        public void abort() {
            int old = status.getAndSet(AWAITING_NONE);
            if (old != AWAITING_NONE) {
                allOf.completeExceptionally(ABORTED);
            }
        }
        
        private boolean implicitComplete() {
            int old = status.getAndUpdate(v -> switch (v) {
                case AWAITING_BOTH -> AWAITING_EXPL;
                case AWAITING_IMPL, AWAITING_NONE -> AWAITING_NONE;
                default -> throw new AssertionError("Unexpected: " + v);
            });
            return old == AWAITING_IMPL;
        }
        
        private boolean explicitComplete() {
            int old = status.getAndUpdate(v -> switch (v) {
                case AWAITING_BOTH -> AWAITING_IMPL;
                case AWAITING_EXPL, AWAITING_NONE -> AWAITING_NONE;
                default -> throw new AssertionError("Unexpected: " + v);
            });
            return old == AWAITING_EXPL;
        }
    }
}