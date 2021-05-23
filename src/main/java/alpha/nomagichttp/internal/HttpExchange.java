package alpha.nomagichttp.internal;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.HttpConstants.Version;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.HttpVersionTooNewException;
import alpha.nomagichttp.message.HttpVersionTooOldException;
import alpha.nomagichttp.message.IllegalBodyException;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.RequestBodyTimeoutException;
import alpha.nomagichttp.message.RequestHeadTimeoutException;
import alpha.nomagichttp.route.RouteRegistry;

import java.nio.channels.InterruptedByTimeoutException;
import java.util.Collection;
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
import static alpha.nomagichttp.message.Responses.continue_;
import static alpha.nomagichttp.util.Headers.accept;
import static alpha.nomagichttp.util.Headers.contentType;
import static alpha.nomagichttp.util.Subscribers.onNext;
import static java.lang.Integer.parseInt;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;
import static java.util.Objects.requireNonNull;

/**
 * Orchestrator of an HTTP exchange from request to response.<p>
 * 
 * Core responsibilities:
 * <ul>
 *   <li>Schedule a request head subscriber on the channel</li>
 *   <li>Create request object and invoke request handler</li>
 *   <li>Handle errors from the exchange by possibly invoking error handlers</li>
 * </ul>
 * 
 * In addition:
 * <ul>
 *   <li>Has package-private accessors for the HTTP version and request object</li>
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
    
    private final Config config;
    private final RouteRegistry registry;
    private final Collection<ErrorHandler> handlers;
    private final ChannelByteBufferPublisher bytes;
    private final ResponsePipeline pipe;
    private final DefaultClientChannel chan;
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
    private Version ver;
    private DefaultRequest request;
    private RequestHandler handler;
    private volatile boolean sent100c;
    private volatile boolean subscriberArrived;
    private ErrorResolver errRes;
    
    HttpExchange(
            Config config,
            RouteRegistry registry,
            Collection<ErrorHandler> handlers,
            ChannelByteBufferPublisher bytes,
            DefaultClientChannel chan)
    {
        this.config   = config;
        this.registry = registry;
        this.handlers = handlers;
        this.bytes    = bytes;
        this.chan     = chan;
        this.pipe     = new ResponsePipeline(this, chan);
        this.cntDown  = new AtomicInteger(2); // <-- request handler + final response
        this.result   = new CompletableFuture<>();
        this.ver      = HTTP_1_1; // <-- default until updated
        this.request  = null;
        this.handler  = null;
        this.sent100c = false;
        this.errRes   = null;
    }
    
    Version getHttpVersion() {
        return ver;
    }
    
    Request getRequest() {
        return request;
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
    
    private void begin0() {
        setupPipeline();
        parseRequestHead()
           .thenAccept(this::initialize)
           .thenRun(() -> { if (config.immediatelyContinueExpect100())
               tryRespond100Continue(); })
           .thenRun(this::invokeRequestHandler)
           .exceptionally(thr -> {
               if (cntDown.decrementAndGet() == 0) {
                   LOG.log(WARNING,
                       "Request handler returned exceptionally but final response already sent. " +
                       "This error is ignored.", thr);
               } else {
                   pipe.startTimeout();
                   handleError(thr);
               }
               return null;
           });
    }
    
    private void setupPipeline() {
        pipe.subscribe(onNext(this::handlePipeResult));
        chan.usePipeline(pipe);
    }
    
    private CompletionStage<RequestHead> parseRequestHead() {
        RequestHeadSubscriber rhs = new RequestHeadSubscriber(config.maxRequestHeadSize());
        
        var to = new TimeoutOp.Flow<>(false, true, bytes,
                config.timeoutIdleConnection(), RequestHeadTimeoutException::new);
        to.subscribe(rhs);
        to.start();
        
        return rhs.asCompletionStage();
    }
    
    private void initialize(RequestHead h) {
        head = h;
        RequestTarget t = RequestTarget.parse(h.requestTarget());
        
        ver = parseHttpVersion(h);
        if (ver == HTTP_1_0 && config.rejectClientsUsingHTTP1_0()) {
            throw new HttpVersionTooOldException(h.httpVersion(), "HTTP/1.1");
        }
        
        RouteRegistry.Match m = findRoute(t);
        
        // This order is actually specified in javadoc of ErrorHandler#apply
        request = createRequest(h, t, m);
                  validateRequest();
                  monitorRequest();
        handler = findRequestHandler(h, m);
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
    
    private RouteRegistry.Match findRoute(RequestTarget t) {
        return registry.lookup(t.segmentsNotPercentDecoded());
    }
    
    private DefaultRequest createRequest(RequestHead h, RequestTarget t, RouteRegistry.Match m) {
        return DefaultRequest.withParams(ver, h, t, m, bytes, chan,
                config.timeoutIdleConnection(),
                this::tryRespond100Continue,
                () -> { subscriberArrived = true; });
    }
    
    private void validateRequest() {
        // May become pre-action
        if (request.method().equals(TRACE) && !request.body().isEmpty()) {
            throw new IllegalBodyException("Body in a TRACE request.", request);
        }
    }
    
    private void monitorRequest() {
        request.bodyStage().whenComplete((Null, thr) -> {
            pipe.startTimeout();
            if (thr instanceof RequestBodyTimeoutException && !subscriberArrived) {
                // Then we need to deal with it
                handleError(thr);
            }
        });
    }
    
    private static RequestHandler findRequestHandler(RequestHead rh, RouteRegistry.Match m) {
        RequestHandler h = m.route().lookup(
                rh.method(),
                contentType(rh.headers()).orElse(null),
                accept(rh.headers()));
        
        LOG.log(DEBUG, () -> "Matched handler: " + h);
        return h;
    }
    
    private void tryRespond100Continue() {
        if (!sent100c &&
            !getHttpVersion().isLessThan(HTTP_1_1) &&
            request.headerContains(EXPECT, "100-continue"))
        {
            chan.write(continue_());
        }
    }
    
    private void invokeRequestHandler() {
        handler.logic().accept(request, chan);
        if (cntDown.decrementAndGet() == 0) {
            LOG.log(DEBUG, "Request handler finished after final response. " +
                           "Preparing for a new HTTP exchange.");
            tryPrepareForNewExchange();
        }
    }
    
    private void handlePipeResult(ResponsePipeline.Result res) {
        if (res.error() != null) {
            handleError(res.error());
        } else if (res.response().isFinal()) {
            // No need to tryRespond100Continue() after this point
            sent100c = true;
            if (cntDown.decrementAndGet() == 0) {
                LOG.log(DEBUG, "Response sent is final. May prepare for a new HTTP exchange.");
                tryPrepareForNewExchange();
            } else {
                LOG.log(DEBUG,
                    "Response sent is final but request handler is still executing. " +
                    "HTTP exchange remains active.");
            }
        } else {
            if (res.response().statusCode() == ONE_HUNDRED) {
                sent100c = true;
            }
            LOG.log(DEBUG, "Response sent is not final. HTTP exchange remains active.");
        }
    }
    
    private void tryPrepareForNewExchange() {
        // Super early failure means no new HTTP exchange
        if (head == null) {
            // e.g. RequestHeadParseException -> 400 (Bad Request)
            if (LOG.isLoggable(DEBUG)) {
                if (chan.isEverythingOpen()) {
                    LOG.log(DEBUG, "No request head parsed. Closing child channel. (end of HTTP exchange)");
                } else {
                    LOG.log(DEBUG, "No request head parsed. (end of HTTP exchange)");
                }
            }
            chan.closeSafe();
            result.complete(null);
            return;
        }
        
        // To have a new HTTP exchange, we must first make sure all of the request body is read
        
        if (!chan.isOpenForReading()) {
            // ...nothing to drain
            LOG.log(DEBUG, "Input stream was shut down. HTTP exchange is over.");
            result.complete(null);
            return;
        }
        
        final DefaultRequest req = request != null ? request :
                // e.g. NoRouteFoundException -> 404 (Not Found)
                // (use local request obj without API support for parameters)
                DefaultRequest.withoutParams(ver, head, bytes, chan, config.timeoutIdleConnection(), null, null);
        
        req.bodyDiscardIfNoSubscriber();
        req.bodyStage().whenComplete((Null, t) -> {
            if (t == null) {
                if (req.headerContains(CONNECTION, "close") && chan.isOpenForReading()) {
                    LOG.log(DEBUG, "Request has \"Connection: close\", shutting down input.");
                    chan.shutdownInputSafe();
                }
                // ResponsePipeline shuts down output on "Connection: close".
                // DefaultServer will not start a new exchange if child or any stream thereof is closed.
                LOG.log(DEBUG, "Normal end of HTTP exchange.");
                result.complete(null);
            } else {
                LOG.log(DEBUG,
                    // see SubscriptionAsStageOp.asCompletionStage()
                    "Request upstream/channel error. " +
                    "Assuming reason and/or stacktrace was logged already. " +
                    "HTTP exchange is over.");
                result.completeExceptionally(t);
            }
        });
    }
    
    // Lock not expected to be contended. But in theory, this method can be
    // invoked concurrently by a synchronous error from the request handler
    // invocation as well as an asynchronous error from the response pipeline.
    // TODO: Improve?
    private synchronized void handleError(Throwable exc) {
        final Throwable unpacked = unpackCompletionException(exc);
        
        if (unpacked instanceof ClientAbortedException) {
            LOG.log(DEBUG, "Client aborted the HTTP exchange.");
            result.completeExceptionally(unpacked);
            return;
        }
        
        if (unpacked instanceof InterruptedByTimeoutException) {
            LOG.log(DEBUG, "Low-level write timed out. Closing channel. (end of HTTP exchange)", unpacked);
            chan.closeSafe();
            result.completeExceptionally(unpacked);
            return;
        }
        
        if (unpacked instanceof RequestHeadTimeoutException) {
            LOG.log(DEBUG, "Request head timed out, shutting down input stream.");
            // HTTP exchange will not continue after response
            // RequestBodyTimeoutException already shut down the input stream (see DefaultRequest)
            chan.shutdownInputSafe();
            // Continue
        }
        
        if (chan.isOpenForWriting())  {
            if (errRes == null) {
                errRes = new ErrorResolver();
            }
            errRes.resolve(unpacked);
        } else {
            LOG.log(WARNING, () ->
                "Child channel is closed for writing. " +
                "Can not resolve this error. " +
                "HTTP exchange is over.", unpacked);
            result.completeExceptionally(unpacked);
        }
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
                ErrorHandler.DEFAULT.apply(t, chan, request, handler);
            } catch (Throwable next) {
                next.addSuppressed(t);
                unexpected(next);
            }
        }
        
        private void usingHandlers(Throwable t) {
            for (ErrorHandler h : handlers) {
                try {
                    h.apply(t, chan, request, handler);
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
    
    private void unexpected(Throwable t) {
        LOG.log(ERROR, "Unexpected.", t);
        result.completeExceptionally(t);
    }
}