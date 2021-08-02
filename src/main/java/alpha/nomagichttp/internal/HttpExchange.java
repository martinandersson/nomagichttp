package alpha.nomagichttp.internal;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.HttpConstants.Version;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.action.BeforeAction;
import alpha.nomagichttp.action.Chain;
import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.HttpVersionTooNewException;
import alpha.nomagichttp.message.HttpVersionTooOldException;
import alpha.nomagichttp.message.IllegalBodyException;
import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.RequestBodyTimeoutException;
import alpha.nomagichttp.message.RequestHead;
import alpha.nomagichttp.message.RequestHeadTimeoutException;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.util.SerialExecutor;

import java.io.IOException;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.InterruptedByTimeoutException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static alpha.nomagichttp.HttpConstants.HeaderKey.CONNECTION;
import static alpha.nomagichttp.HttpConstants.HeaderKey.EXPECT;
import static alpha.nomagichttp.HttpConstants.Method.TRACE;
import static alpha.nomagichttp.HttpConstants.StatusCode.ONE_HUNDRED;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_0;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.internal.ResponsePipeline.Error;
import static alpha.nomagichttp.internal.ResponsePipeline.Success;
import static alpha.nomagichttp.message.Responses.continue_;
import static alpha.nomagichttp.util.Headers.accept;
import static alpha.nomagichttp.util.Headers.contentType;
import static alpha.nomagichttp.util.IOExceptions.isCausedByBrokenInputStream;
import static alpha.nomagichttp.util.IOExceptions.isCausedByBrokenOutputStream;
import static java.lang.Integer.parseInt;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedStage;

/**
 * Orchestrator of an HTTP exchange from request to response.<p>
 * 
 * Core responsibilities:
 * <ul>
 *   <li>Schedule a request head subscriber on the channel</li>
 *   <li>Invoke before-action(s) and request handler</li>
 *   <li>Invoke error handlers</li>
 * </ul>
 * 
 * In addition:
 * <ul>
 *   <li>Has package-private accessors for the HTTP version and request head</li>
 *   <li>May pre-emptively send a 100 (Continue) depending on configuration</li>
 *   <li>Shuts down read stream if request has header "Connection: close"</li>
 *   <li>Shuts down read stream on request timeout</li>
 * </ul>
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class HttpExchange
{
    private static final System.Logger LOG = System.getLogger(HttpExchange.class.getPackageName());
    private static final CompletionStage<Void> COMPLETED = completedStage(null);
    private static final Throwable ABORT = new Throwable();
    
    private final HttpServer server;
    private final Config config;
    private final DefaultActionRegistry actions;
    private final DefaultRouteRegistry routes;
    private final Collection<ErrorHandler> handlers;
    private final ChannelByteBufferPublisher chIn;
    private final ResponsePipeline pipe;
    private final DefaultClientChannel chApi;
    private final AtomicInteger cntDown;
    private final CompletableFuture<Void> result;
    
    /*
     * Mutable fields related to the request chain in this class are not
     * volatile nor synchronized. It is assumed that the asynchronous execution
     * facility of the CompletionStage implementation establishes a
     * happens-before relationship. This is certainly true for JDK's
     * CompletableFuture which uses an Executor/ExecutorService, or at worst, a
     * new Thread.start() for each task.
     */
    
    private RequestHead head;
    private RequestTarget target;
    private Version ver;
    private RequestBody body;
    private DefaultRequest request;
    private RequestHandler handler;
    private volatile boolean sent100c;
    private volatile boolean subscriberArrived;
    private ErrorResolver errRes;
    
    HttpExchange(
            HttpServer server,
            DefaultActionRegistry actions,
            DefaultRouteRegistry routes,
            Collection<ErrorHandler> handlers,
            ChannelByteBufferPublisher chIn,
            DefaultClientChannel chApi)
    {
        this.server   = server;
        this.config   = server.getConfig();
        this.actions  = actions;
        this.routes   = routes;
        this.handlers = handlers;
        this.chIn     = chIn;
        this.chApi    = chApi;
        this.pipe     = new ResponsePipeline(this, chApi);
        this.cntDown  = new AtomicInteger(2); // <-- request handler + final response, then new exchange
        this.result   = new CompletableFuture<>();
        this.ver      = HTTP_1_1; // <-- default until updated
    }
    
    /**
     * Returns the active HTTP version.<p>
     * 
     * Before having been parsed and accepted from the request, this method
     * returns a default {@link HttpConstants.Version#HTTP_1_1}. The value may
     * consequently be updated both down and up.
     * 
     * @return the active HTTP version
     */
    Version getHttpVersion() {
        return ver;
    }
    
    /**
     * Returns the parsed request head, if available, otherwise {@code null}.
     * 
     * @return the parsed request head, if available, otherwise {@code null}
     */
    RequestHead getRequestHead() {
        return head;
    }
    
    /**
     * Begin the exchange.<p>
     * 
     * The returned stage should mostly complete normally as HTTP exchange
     * errors are dealt with internally (through {@link ErrorHandler}). Any
     * other error will be logged in this class. The server should close the
     * child if the result completes exceptionally.
     * 
     * @return a stage (never {@code null})
     */
    CompletionStage<Void> begin() {
        LOG.log(DEBUG, "Beginning a new HTTP exchange.");
        try {
            begin0();
        } catch (Throwable t) {
            unexpected(t);
        }
        return result;
    }
    
    private void unexpected(Throwable t) {
        LOG.log(ERROR, "Unexpected.", t);
        result.completeExceptionally(t);
    }
    
    private void begin0() {
        setupPipeline();
        parseRequestHead()
           .thenAccept(this::initialize)
           .thenRun(() -> { if (config.immediatelyContinueExpect100())
               tryRespond100Continue(); })
           .thenCompose(nil -> invokeBeforeActions())
           .thenRun(this::invokeRequestHandler)
           .whenComplete((nil, thr) -> handleChainCompletion(thr));
    }
    
    private void setupPipeline() {
        pipe.on(Success.class, (ev, rsp) -> handleWriteSuccess((Response) rsp));
        pipe.on(Error.class, (ev, thr) -> handleError((Throwable) thr));
        chApi.usePipeline(pipe);
    }
    
    private CompletionStage<RequestHead> parseRequestHead() {
        RequestHeadSubscriber rhs = new RequestHeadSubscriber(server);
        
        var to = new TimeoutOp.Flow<>(false, true, chIn,
                config.timeoutIdleConnection(), RequestHeadTimeoutException::new);
        to.subscribe(rhs);
        to.start();
        
        return rhs.asCompletionStage();
    }
    
    private void initialize(RequestHead h) {
        head = h;
        target = RequestTarget.parse(h.requestTarget());
        
        ver = parseHttpVersion(h);
        if (ver == HTTP_1_0 && config.rejectClientsUsingHTTP1_0()) {
            throw new HttpVersionTooOldException(h.httpVersion(), "HTTP/1.1");
        }
        
        body = createBody(h);
               monitorBody();
    }
    
    private static Version parseHttpVersion(RequestHead h) {
        final Version v;
        
        try {
            v = Version.parse(h.httpVersion());
        } catch (IllegalArgumentException e) {
            String[] comp = e.getMessage().split(":");
            if (comp.length == 1) {
                // No literal for minor
                requireHTTP1(parseInt(comp[0]), h.httpVersion(), "HTTP/1.1"); // for now
                throw new AssertionError(
                        "String \"HTTP/<single digit>\" should have failed with parse exception (missing minor).");
            } else {
                // No literal for major + minor (i.e., version older than HTTP/0.9)
                assert comp.length == 2;
                assert parseInt(comp[0]) <= 0;
                throw new HttpVersionTooOldException(h.httpVersion(), "HTTP/1.1");
            }
        }
        
        requireHTTP1(v.major(), h.httpVersion(), "HTTP/1.1");
        return v;
    }
    
    private static void requireHTTP1(int major, String rejectedVersion, String upgrade) {
        if (major < 1) {
            throw new HttpVersionTooOldException(rejectedVersion, upgrade);
        }
        if (major > 1) { // for now
            throw new HttpVersionTooNewException(rejectedVersion);
        }
    }
    
    private RequestBody createBody(RequestHead h) {
        return RequestBody.of(h.headers(), chIn, chApi,
                config.timeoutIdleConnection(),
                this::tryRespond100Continue,
                () -> subscriberArrived = true);
    }
    
    private void monitorBody() {
        body.asCompletionStage().whenComplete((nil, thr) -> {
            // Note, an empty body is immediately completed
            pipe.startTimeout();
            if (thr instanceof RequestBodyTimeoutException && !subscriberArrived) {
                // Then we need to deal with it
                handleError(thr);
            }
        });
    }
    
    private void tryRespond100Continue() {
        if (!sent100c &&
                !getHttpVersion().isLessThan(HTTP_1_1) &&
                head.headerContains(EXPECT, "100-continue"))
        {
            chApi.write(continue_());
        }
    }
    
    private CompletionStage<Void> invokeBeforeActions() {
        List<ResourceMatch<BeforeAction>> matches = actions.lookupBefore(target);
        if (matches.isEmpty()) {
            return COMPLETED;
        }
        CompletableFuture<Void> res = new CompletableFuture<>();
        
        // TODO: implement
        res.complete(null);
        
        return res;
    }
    
    private void invokeRequestHandler() {
        ResourceMatch<Route> route = routes.lookup(target);
        
        request = createRequest(route);
                  validateRequest();
        handler = findRequestHandler(head, route);
        
        handler.logic().accept(request, chApi);
    }
    
    private DefaultRequest createRequest(ResourceMatch<?> resource) {
        return new DefaultRequest(ver, head, body,
                   new DefaultParameters(resource, target),
                   new DefaultAttributes());
    }
    
    private void validateRequest() {
        // MAY become before-action
        if (request.method().equals(TRACE) && !request.body().isEmpty()) {
            throw new IllegalBodyException("Body in a TRACE request.", request);
        }
    }
    
    private static RequestHandler findRequestHandler(RequestHead rh, ResourceMatch<Route> m) {
        MediaType type = contentType(rh.headers()).orElse(null);
        // TODO: Find a way to cache this and re-use in Responses factories that
        //       parse a charset from request (in this branch)
        MediaType[] accepts = accept(rh.headers())
                .map(s -> s.toArray(MediaType[]::new))
                .orElse(null);
        RequestHandler h = m.get().lookup(rh.method(), type, accepts);
        LOG.log(DEBUG, () -> "Matched handler: " + h);
        return h;
    }
    
    private void handleChainCompletion(Throwable thr) {
        final int v = cntDown.decrementAndGet();
        if (thr == null || thr.getCause() == ABORT) {
            if (v == 0) {
                LOG.log(DEBUG, "Invocation chain finished after final response. " +
                               "Preparing for a new HTTP exchange.");
                tryPrepareForNewExchange();
            } // normal finish = do nothing, final response will try to prepare next
        } else {
            if (v == 0) {
                LOG.log(WARNING,
                        "Processing returned exceptionally but final response already sent. " +
                        "This error is ignored. " +
                        "Preparing for a new HTTP exchange.", thr);
                tryPrepareForNewExchange();
            } else {
                pipe.startTimeout();
                handleError(thr);
            }
        }
    }
    
    private void handleWriteSuccess(Response rsp) {
        if (rsp.isFinal()) {
            // No need to tryRespond100Continue() after this point
            sent100c = true;
            if (cntDown.decrementAndGet() == 0) {
                LOG.log(DEBUG, "Response sent is final. May prepare for a new HTTP exchange.");
                tryPrepareForNewExchange();
            } else {
                LOG.log(DEBUG,
                    "Response sent is final but request's processing chain is still executing. " +
                    "HTTP exchange remains active.");
            }
        } else {
            if (rsp.statusCode() == ONE_HUNDRED) {
                sent100c = true;
            }
            LOG.log(DEBUG, "Response sent is not final. HTTP exchange remains active.");
        }
    }
    
    private void tryPrepareForNewExchange() {
        if (!chApi.isOpenForReading()) {
            LOG.log(DEBUG, "Input stream was shut down. HTTP exchange is over.");
            result.complete(null);
            return;
        }
        
        // Super early failure means no new HTTP exchange
        if (head == null) {
            // e.g. RequestHeadParseException -> 400 (Bad Request)
            if (LOG.isLoggable(DEBUG)) {
                if (chApi.isEverythingOpen()) {
                    LOG.log(DEBUG, "No request head parsed. Closing child channel. (end of HTTP exchange)");
                } else {
                    LOG.log(DEBUG, "No request head parsed. (end of HTTP exchange)");
                }
            }
            chApi.closeSafe();
            result.complete(null);
            return;
        }
        
        // To have a new HTTP exchange, we must first make sure the body is consumed
        
        final var b = body != null ? body :
                // e.g. HttpVersionTooOldException -> 426 (Upgrade Required)
                // (use a local dummy)
                RequestBody.of(head.headers(), chIn, chApi, null, null, null);
        
        b.discardIfNoSubscriber();
        b.asCompletionStage().whenComplete((nil, thr) -> {
            // Prepping new exchange = thr is ignored (already dealt with, hopefully lol)
            if (head.headerContains(CONNECTION, "close") && chApi.isOpenForReading()) {
                LOG.log(DEBUG, "Request set \"Connection: close\", shutting down input.");
                chApi.shutdownInputSafe();
            }
            // Note
            //   ResponsePipeline shuts down output on "Connection: close".
            //   DefaultServer will not start a new exchange if child or any stream thereof is closed.
            LOG.log(DEBUG, "Normal end of HTTP exchange.");
            result.complete(null);
        });
    }
    
    private final SerialExecutor serially = new SerialExecutor();
    
    private void handleError(Throwable exc) {
        final Throwable unpacked = unpackCompletionException(exc);
        if (isTerminatingException(unpacked, chApi)) {
            result.completeExceptionally(exc);
            return;
        }
        
        if (unpacked instanceof RequestHeadTimeoutException) {
            LOG.log(DEBUG, "Request head timed out, shutting down input stream.");
            // HTTP exchange will not continue after response
            // RequestBodyTimeoutException already shut down the input stream (see DefaultRequest)
            chApi.shutdownInputSafe();
        }
        
        if (chApi.isOpenForWriting())  {
            // We don't expect errors to be arriving concurrently from the same
            // exchange, this would be kind of weird. But technically, it could
            // happen, e.g. a synchronous error from the request handler and an
            // asynchronous error from the response pipeline. That's the only
            // reason a serial executor is used here. It's either that or the
            // synchronized keyword. The latter normally being the preferred
            // choice when the lock is expected to not be contended. However,
            // the thread is likely the server's request thread, and this guy
            // must under no circumstances - ever - be blocked.
            serially.execute(() -> {
                if (errRes == null) {
                    errRes = new ErrorResolver();
                }
                errRes.resolve(unpacked);
            });
        } else {
            LOG.log(WARNING, () ->
                "Child channel is closed for writing. " +
                "Can not resolve this error. " +
                "HTTP exchange is over.", unpacked);
            result.completeExceptionally(unpacked);
        }
    }
    
    /**
     * Returns {@code true} if it is meaningless to attempt resolving the
     * exception and/or logging it would just be noise.<p>
     * 
     * If this method returns true and need be, then this method will also have
     * logged a DEBUG message.
     * 
     * @param thr to examine
     * @param chan child channel
     * @return see JavaDoc
     */
    private static boolean isTerminatingException(Throwable thr, ClientChannel chan) {
        if (thr instanceof ClientAbortedException) {
            LOG.log(DEBUG, "Client aborted the HTTP exchange.");
            return true;
        }
        
        if (!(thr instanceof IOException)) {
            // EndOfStreamException goes to error handler
            return false;
        }
        
        // Okay we've got an I/O error, but is it from our child?
        if (chan.isEverythingOpen()) {
            // Nope. AnnounceToChannel closes the stream. Has to be from application code.
            // (we should probably examine stacktrace or mark our exceptions somehow)
            return false;
        }
        
        if (thr instanceof InterruptedByTimeoutException) {
            LOG.log(DEBUG, "Low-level write timed out. Closing channel. (end of HTTP exchange)");
            chan.closeSafe();
            return true;
        }
        
        if (thr instanceof AsynchronousCloseException) {
            // No need to log anything. Outstanding async operation was aborted
            // because channel closed already. I.e. exchange ended already, and
            // we assume that the reason for closing and ending the exchange has
            // already been logged. E.g. timeout exceptions will cause this.
            return true;
        }
        
        var io = (IOException) thr;
        if (isCausedByBrokenInputStream(io) || isCausedByBrokenOutputStream(io)) {
            LOG.log(DEBUG, "Broken pipe, closing channel. (end of HTTP exchange)");
            chan.closeSafe();
            return true;
        }
        
        // From our child, yes, but it can not be deduced as abruptly terminating
        // and so we'd rather pass it to the error handler or log it.
        // E.g., if app write a response on a closed channel (ClosedChannelException),
        // then we still want to log the problem - actually required by
        // ClientChannel.write().
        // See test "ClientLifeCycleTest.serverClosesChannel_beforeResponse()".
        return false;
    }
    
    private class ErrorResolver {
        private Throwable prev;
        private int attemptCount;
        
        ErrorResolver() {
            this.attemptCount = 0;
        }
        
        void resolve(Throwable t) {
            if (prev != null) {
                assert prev != t;
                t.addSuppressed(prev);
            }
            prev = t;
            
            if (handlers.isEmpty()) {
                usingDefault(t);
                return;
            }
            
            if (++attemptCount > config.maxErrorRecoveryAttempts()) {
                LOG.log(WARNING, "Error recovery attempts depleted, will use default handler.");
                usingDefault(t);
                return;
            }
            
            LOG.log(DEBUG, () -> "Attempting error recovery #" + attemptCount);
            usingHandlers(t);
        }
        
        private void usingDefault(Throwable t) {
            try {
                ErrorHandler.DEFAULT.apply(t, chApi, request, handler);
            } catch (Throwable next) {
                next.addSuppressed(t);
                unexpected(next);
            }
        }
        
        private void usingHandlers(Throwable t) {
            for (ErrorHandler h : handlers) {
                try {
                    h.apply(t, chApi, request, handler);
                    return;
                } catch (Throwable next) {
                    if (t != next) {
                        // New fail
                        HttpExchange.this.handleError(next);
                        return;
                    } // else continue; Handler opted out
                }
            }
            // All handlers opted out
            usingDefault(t);
        }
    }
    
    private static Throwable unpackCompletionException(Throwable t) {
        requireNonNull(t);
        if (!(t instanceof CompletionException)) {
            return t;
        }
        return t.getCause() == null ? t : unpackCompletionException(t.getCause());
    }
    
    private static class DefaultChain<A> implements Chain {
        
        private final List<ResourceMatch<A>> actions;
        
        DefaultChain(List<ResourceMatch<A>> actions) {
            this.actions = actions;
        }
        
        @Override
        public void proceed() {
            // TODO: implement
        }
    
        @Override
        public void abort() {
            // TODO: implement
        }
    }
}