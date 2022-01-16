package alpha.nomagichttp.internal;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.HttpConstants.Version;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.event.RequestHeadReceived;
import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.internal.ResponsePipeline.Command;
import alpha.nomagichttp.message.DefaultContentHeaders;
import alpha.nomagichttp.message.HttpVersionTooNewException;
import alpha.nomagichttp.message.HttpVersionTooOldException;
import alpha.nomagichttp.message.IllegalRequestBodyException;
import alpha.nomagichttp.message.RawRequest;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.RequestHeadTimeoutException;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.util.SerialExecutor;

import java.io.IOException;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.InterruptedByTimeoutException;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static alpha.nomagichttp.HttpConstants.HeaderName.CONNECTION;
import static alpha.nomagichttp.HttpConstants.HeaderName.EXPECT;
import static alpha.nomagichttp.HttpConstants.Method.TRACE;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_0;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.handler.ErrorHandler.DEFAULT;
import static alpha.nomagichttp.internal.HeadersSubscriber.forRequestHeaders;
import static alpha.nomagichttp.internal.InvocationChain.ABORTED;
import static alpha.nomagichttp.internal.ResponsePipeline.Error;
import static alpha.nomagichttp.internal.ResponsePipeline.Success;
import static alpha.nomagichttp.internal.SubscriptionMonitoringOp.Reason.UPSTREAM_ERROR_NOT_DELIVERED;
import static alpha.nomagichttp.util.IOExceptions.isCausedByBrokenInputStream;
import static alpha.nomagichttp.util.IOExceptions.isCausedByBrokenOutputStream;
import static java.lang.Integer.parseInt;
import static java.lang.Math.addExact;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;
import static java.lang.System.nanoTime;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.allOf;

/**
 * Orchestrator of an HTTP exchange from request to response, erm, "ish".<p>
 * 
 * Core responsibilities:
 * <ul>
 *   <li>Setup channel with a new response pipeline</li>
 *   <li>Schedule a request head subscriber on the channel</li>
 *   <li>Trigger the invocation chain</li>
 *   <li>When invocation chain and response pipeline completes, prepare a new exchange</li>
 *   <li>Hand off all errors to the error handler(s)</li>
 * </ul>
 * 
 * In addition:
 * <ul>
 *   <li>Has package-private accessors for the request head, HTTP version and request skeleton</li>
 *   <li>May pre-emptively schedule a 100 (Continue) depending on configuration</li>
 *   <li>Shuts down read stream if request has header "Connection: close"</li>
 *   <li>Shuts down read stream on {@link RequestHeadTimeoutException}</li>
 * </ul>
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class HttpExchange
{
    private static final System.Logger LOG = System.getLogger(HttpExchange.class.getPackageName());
    
    /**
     * Event emitted by the collaborators {@link InvocationChain} and
     * {@link ResponsePipeline} whenever a request object has been created. The
     * first (and only) attachment is the request object created. The result
     * performed by this class is an update of the HTTP exchange state, so that
     * the {@link ErrorHandler} can observe the most recent instance.
     */
    enum RequestCreated { INSTANCE }
    
    private final HttpServer server;
    private final Config config;
    private final Collection<ErrorHandler> handlers;
    private final ChannelByteBufferPublisher chIn;
    private final DefaultClientChannel chApi;
    private final InvocationChain chain;
    private final ResponsePipeline pipe;
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
    
    private RawRequest.Head head;
    private Version version;
    private SkeletonRequest reqThin;
    private Request reqFat;
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
        this.handlers = handlers;
        this.chIn     = chIn;
        this.chApi    = chApi;
        this.chain    = new InvocationChain(actions, routes, chApi);
        this.pipe     = new ResponsePipeline(this, chApi, actions);
        this.cntDown  = new AtomicInteger(2); // <-- invocation chain + final response, then prepareForNewExchange()
        this.result   = new CompletableFuture<>();
        this.version  = HTTP_1_1; // <-- default until updated
    }
    
    /**
     * Returns the parsed request head, if available, otherwise {@code null}.
     * 
     * @return the parsed request head, if available, otherwise {@code null}
     */
    RawRequest.Head getRequestHead() {
        return head;
    }
    
    /**
     * Returns the active HTTP version.<p>
     * 
     * Before the version has been parsed and accepted from the request, this
     * method returns a default {@link HttpConstants.Version#HTTP_1_1}. The
     * value may subsequently be updated both down and up.
     * 
     * @return the active HTTP version
     */
    Version getHttpVersion() {
        return version;
    }
    
    /**
     * Returns the request, if available, otherwise {@code null}.
     * 
     * @return the request, if available, otherwise {@code null}
     */
    SkeletonRequest getSkeletonRequest() {
        return reqThin;
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
        subscribeToRequestObjects();
        parseRequestLine()
            .thenCompose(this::parseRequestHeaders)
            .thenAccept(this::initialize)
            .thenRun(() -> { if (config.immediatelyContinueExpect100())
                tryRespond100Continue(); })
            .thenCompose(nil -> chain.execute(reqThin, version))
            .whenComplete((nil, thr) -> handleChainCompletion(thr));
    }
    
    private void setupPipeline() {
        pipe.on(Success.class, (ev, rsp) -> handleWriteSuccess((Response) rsp));
        pipe.on(Error.class, (ev, thr) -> {
            LOG.log(DEBUG, "Response pipeline failed. Handling the error.");
            handleError((Throwable) thr);
        });
        chApi.usePipeline(pipe);
    }
    
    private void subscribeToRequestObjects() {
        BiConsumer<RequestCreated, Request> save = (ev, req) -> reqFat = req;
        chain.on(RequestCreated.class, save);
        pipe.on(RequestCreated.class, save);
    }
    
    private CompletionStage<RawRequest.Line> parseRequestLine() {
        RequestLineSubscriber rls = new RequestLineSubscriber(
                config.maxRequestHeadSize(), chApi);
        var to = new TimeoutOp.Flow<>(false, true, chIn, config.timeoutIdleConnection(), () -> {
            // No new HTTP exchange
            if (chApi.isOpenForReading()) {
                LOG.log(DEBUG, "Request head timed out, shutting down child channel's read stream.");
                chApi.shutdownInputSafe();
            }
            return new RequestHeadTimeoutException();
        });
        to.subscribe(rls);
        to.start();
        return rls.result();
    }
    
    private CompletionStage<RawRequest.Head> parseRequestHeaders(RawRequest.Line l) {
        HeadersSubscriber<Request.Headers> sub = forRequestHeaders(
                l.length(), config.maxRequestHeadSize(), chApi);
        // TODO: DRY from parseRequestLine(),
        //       but we just might rework the entire timeout plumbing.
        var to = new TimeoutOp.Flow<>(false, true, chIn, config.timeoutIdleConnection(), () -> {
            // No new HTTP exchange
            if (chApi.isOpenForReading()) {
                LOG.log(DEBUG, "Request head timed out, shutting down child channel's read stream.");
                chApi.shutdownInputSafe();
            }
            return new RequestHeadTimeoutException();
        });
        to.subscribe(sub);
        to.start();
        return sub.result().thenApply(headers -> {
            var h = new RawRequest.Head(l, headers);
            server.events().dispatchLazy(RequestHeadReceived.INSTANCE, () -> h, () ->
                    new RequestHeadReceived.Stats(
                            l.nanoTimeOnStart(),
                            nanoTime(),
                            addExact(l.length(), sub.read())));
            return h;
        });
    }
    
    private void initialize(RawRequest.Head h) {
        head = h;
        
        version = parseHttpVersion(h.line().httpVersion());
        if (version == HTTP_1_0 && config.rejectClientsUsingHTTP1_0()) {
            throw new HttpVersionTooOldException(h.line().httpVersion(), "HTTP/1.1");
        }
        
        reqThin = new SkeletonRequest(h,
                SkeletonRequestTarget.parse(h.line().target()),
                monitorBody(createBody(h)),
                new DefaultAttributes());
        
        validateRequest();
    }
    
    private static Version parseHttpVersion(String httpVersion) {
        final Version v;
        
        try {
            v = Version.parse(httpVersion);
        } catch (IllegalArgumentException e) {
            String[] comp = e.getMessage().split(":");
            if (comp.length == 1) {
                // No literal for minor
                requireHTTP1(parseInt(comp[0]), httpVersion, "HTTP/1.1"); // for now
                throw new AssertionError(
                        "String \"HTTP/<single digit>\" should have failed with parse exception (missing minor).");
            } else {
                // No literal for major + minor (i.e., version older than HTTP/0.9)
                assert comp.length == 2;
                assert parseInt(comp[0]) <= 0;
                throw new HttpVersionTooOldException(httpVersion, "HTTP/1.1");
            }
        }
        
        requireHTTP1(v.major(), httpVersion, "HTTP/1.1");
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
    
    private RequestBody createBody(RawRequest.Head h) {
        return RequestBody.of((DefaultContentHeaders) h.headers(), chIn, chApi,
                config.maxRequestTrailersSize(),
                config.timeoutIdleConnection(),
                this::tryRespond100Continue);
    }
    
    private RequestBody monitorBody(RequestBody rb) {
        var bodySub = rb.subscriptionMonitor().asCompletionStage().toCompletableFuture();
        var trailers = rb.trailers().toCompletableFuture();
        allOf(bodySub, trailers).whenComplete((nil, thr) -> {
            // Note, an empty body is immediately completed normally
            pipe.add(Command.INIT_RESPONSE_TIMER);
            assert bodySub.isDone();
            var terminated = bodySub.getNow(null);
            var reason = terminated.reason();
            if (reason == UPSTREAM_ERROR_NOT_DELIVERED) {
                // Then we need to deal with it
                LOG.log(DEBUG, """
                    Body processing finished, but upstream error was not \
                    delivered. Handling the error.""");
                assert terminated.error().isPresent();
                handleError(terminated.error().get());
                // Note, we could also throw in a check for DOWNSTREAM_FAILED.
                // But if we do, that error could end up being handled twice coz
                // the same exception may also complete exceptionally the
                // invocation chain. Not that handling the same error twice
                // would have a devastating effect, but it would clearly be
                // weird for anyone noticing it, and, it makes our test cases
                // less deterministic and hard to write. Further, it has
                // clearly been noted in the JavaDoc of Request.Body that the
                // subscriber should not throw an exception.
            } else {
                LOG.log(DEBUG, () -> "Body processing finished (" + reason + ")");
                // If <thr> is not null, trailers completed exceptionally, and
                // there is no guarantee that the application has even accessed
                // the trailer stage. So, not dealing with <thr> here means the
                // exception could theoretically not be handled.
                //    There's no easy way to provide an error-handling
                // guarantee. We can not know for sure what the application will
                // do in the future. Any imposed "magic" here may instead end up
                // preempting the application's planed final response causing it
                // to be ignored.
                //    An exception lost ought to be an extremely rare event. It
                // can safely be assumed that any endpoint receiving trailers
                // will also arrange for them to be consumed and consequently be
                // in charge of translating the exceptional outcome.
                //     Nor is this really a problem. The application must at
                // some point produce a final response, or else face a timeout.
                // And that is good enough. Logging it here means it'll never be
                // completely lost.
                if (thr != null) {
                    LOG.log(WARNING, () ->
                        "Request trailers finished exceptionally: " +
                        unpackCompletionException(thr));
                }
            }
        });
        return rb;
    }
    
    private void validateRequest() {
        // Is NOT suitable as a before-action; they are invoked only for valid requests
        if (head.line().method().equals(TRACE) && !reqThin.body().isEmpty()) {
            throw new IllegalRequestBodyException(
                    head, reqThin.body(), "Body in a TRACE request.");
        }
    }
    
    private void tryRespond100Continue() {
        if (!getHttpVersion().isLessThan(HTTP_1_1) &&
            head.headers().contain(EXPECT, "100-continue")) {
            pipe.add(Command.TRY_SCHEDULE_100CONTINUE);
        }
    }
    
    private void handleChainCompletion(Throwable thr) {
        final int v = cntDown.decrementAndGet();
        if (thr == null || thr.getCause() == ABORTED) {
            if (v == 0) {
                LOG.log(DEBUG, """
                    Invocation chain finished normally after final response. \
                    Preparing for a new HTTP exchange.""");
                prepareForNewExchange();
            } // else normal finish = do nothing, final response will try to prepare next
        } else {
            if (v == 0) {
                LOG.log(WARNING, """
                    Invocation chain returned exceptionally but final response \
                    was already sent. Will ignore the error and prepare for a \
                    new HTTP exchange.""", thr);
                prepareForNewExchange();
            } else {
                LOG.log(DEBUG, """
                    Invocation chain finished exceptionally, \
                    handling the error.""");
                handleError(thr);
            }
        }
    }
    
    private void handleWriteSuccess(Response rsp) {
        if (!rsp.isFinal()) {
            return;
        }
        if (cntDown.decrementAndGet() == 0) {
            LOG.log(DEBUG,
                "Response sent is final. Preparing for a new HTTP exchange.");
            prepareForNewExchange();
        } else {
            LOG.log(DEBUG, """
                Response sent is final but the invocation chain is \
                still executing. Not preparing for a new exchange.""");
        }
    }
    
    private void prepareForNewExchange() {
        // To have a new HTTP exchange, we must first make sure the body is
        // consumed either normally or through us discarding it. But if the
        // read stream is not open, well no point in continuing at all. Let the
        // server close the channel.
        //    Note: We don't care about the state of the write stream. It may
        // very well be shut down and so there will be no new exchange, but we
        // still must not kill an ongoing inbound request. The body will be
        // discarded only if no subscriber is active. Then after body
        // completion, will we also complete the active exchange. At that point
        // the server will close the channel because not everything remained
        // open.
        
        if (!chApi.isOpenForReading()) {
            LOG.log(DEBUG, "Input stream was shut down. HTTP exchange is over.");
            result.complete(null);
            return;
        }
        
        // Super early failure means no new HTTP exchange
        if (head == null) {
            // e.g. RequestLineParseException -> 400 (Bad Request)
            if (LOG.isLoggable(DEBUG)) {
                if (chApi.isEverythingOpen()) {
                    LOG.log(DEBUG, """
                        No request head parsed. Closing child channel. \
                        (end of HTTP exchange)""");
                } else {
                    LOG.log(DEBUG,
                        "No request head parsed. (end of HTTP exchange)");
                }
            }
            chApi.closeSafe();
            result.complete(null);
            return;
        }
        
        final var b = reqThin != null ? reqThin.body() :
                // E.g. HttpVersionTooOldException -> 426 (Upgrade Required).
                // So we fall back to a local dummy, just for the API.
                RequestBody.of((DefaultContentHeaders) head.headers(),
                        chIn, chApi, -1, null, null);
        
        b.discardIfNoSubscriber();
        b.subscriptionMonitor().asCompletionStage().whenComplete((ign,ored) -> {
            if (head.headers().contain(CONNECTION, "close") && chApi.isOpenForReading()) {
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
    
    private final SerialExecutor serially = new SerialExecutor(true);
    
    private void handleError(Throwable exc) {
        final Throwable unpacked = unpackCompletionException(exc);
        if (isTerminatingException(unpacked, chApi)) {
            result.completeExceptionally(unpacked);
            return;
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
        
        if (!(thr instanceof IOException io)) {
            // EndOfStreamException goes to error handler
            return false;
        }
        
        // Okay we've got an I/O error (!), but is it from our child?
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
        
        if (isCausedByBrokenInputStream(io) || isCausedByBrokenOutputStream(io)) {
            LOG.log(DEBUG, "Broken pipe, closing channel. (end of HTTP exchange)");
            chan.closeSafe();
            return true;
        }
        
        // IOExc from our child, yes, but it can not be deduced as abruptly
        // terminating and so we'd rather pass it to the error handler (maybe)
        // or log it.
        //    For ex., if app write a response on a closed channel
        // (ClosedChannelException), it will not pass to an error handler but it
        // will be logged and thereafter end the HTTP exchange (also see JavaDoc
        // of ClientChannel.write() and test case
        // ClientLifeCycleTest.serverClosesChannel_beforeResponse().
        return false;
    }
    
    private class ErrorResolver {
        private Throwable prev;
        private int attemptCount;
        
        void resolve(Throwable t) {
            // Unlike Java's try-with-resources which propagates the block error
            // and suppresses the subsequent close error - for synchronous
            // errors/recursive calls, our "propagation" is an attempt of
            // resolving the new, more recent error, having suppressed the old.
            if (prev != null) {
                assert prev != t;
                t.addSuppressed(prev);
            }
            prev = t;
            try {
                resolve0(t);
            } finally {
                // New synchronous errors are immediately and recursively
                // resolved. A return from resolve0() means that the error is
                // now considered handled.
                prev = null;
            }
        }
        
        private void resolve0(Throwable t) {
            if (handlers.isEmpty()) {
                usingDefault(t);
                return;
            }
            
            if (++attemptCount > config.maxErrorRecoveryAttempts()) {
                LOG.log(ERROR,
                    "Error recovery attempts depleted, will close the channel. " +
                    "This error is ignored.", t);
                chApi.closeSafe();
                return;
            }
            
            LOG.log(DEBUG, () -> "Attempting error recovery #" + attemptCount);
            usingHandlers(t);
        }
        
        private void usingDefault(Throwable t) {
            try {
                DEFAULT.apply(t, chApi, reqFat);
            } catch (Throwable next) {
                next.addSuppressed(t);
                unexpected(next);
            }
        }
        
        private void usingHandlers(Throwable t) {
            for (ErrorHandler h : handlers) {
                try {
                    h.apply(t, chApi, reqFat);
                    return;
                } catch (Throwable next) {
                    if (t != next) {
                        LOG.log(DEBUG, """
                            Application error handler returned exceptionally. \
                            Handling new error.""");
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
}