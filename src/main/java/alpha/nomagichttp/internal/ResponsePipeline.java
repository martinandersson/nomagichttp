package alpha.nomagichttp.internal;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.action.AfterAction;
import alpha.nomagichttp.handler.ResponseRejectedException;
import alpha.nomagichttp.message.HeaderHolder;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.ResponseTimeoutException;
import alpha.nomagichttp.util.SeriallyRunnable;

import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedDeque;

import static alpha.nomagichttp.HttpConstants.HeaderKey.CONNECTION;
import static alpha.nomagichttp.HttpConstants.HeaderKey.CONTENT_LENGTH;
import static alpha.nomagichttp.HttpConstants.StatusCode.ONE_HUNDRED;
import static alpha.nomagichttp.HttpConstants.StatusCode.isClientError;
import static alpha.nomagichttp.HttpConstants.StatusCode.isServerError;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.handler.ResponseRejectedException.Reason.PROTOCOL_NOT_SUPPORTED;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;
import static java.util.concurrent.CompletableFuture.completedStage;
import static java.util.concurrent.CompletableFuture.failedStage;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Enqueues responses and schedules them to be written out on a client
 * channel.<p>
 * 
 * The {@code write} methods of {@link DefaultClientChannel} is a direct facade
 * for the {@code add} methods declared in this class.<p>
 * 
 * The lifetime of a pipeline is scoped to the HTTP exchange and not to the
 * channel. The final response is the last response accepted by the pipeline.
 * The channel will be updated with a new delegate pipeline instance at the
 * start of each new HTTP exchange.<p>
 * 
 * Core responsibilities:
 * <ul>
 *   <li>From a queue of responses, schedule channel write operations</li>
 *   <li>Invoke all after-actions</li>
 *   <li>Apply low-level response transformations such as setting "Connection:
 *       close" if no "Content-Length"</li>
 *   <li>Act on response-scheduled channel commands (must-close-after-write,
 *       "Connection: close", et cetera)</li>
 * </ul>
 * 
 * In addition:
 * <ol>
 *   <li>Throws high-level {@link ResponseTimeoutException}s (channel delay and
 *       response body emission delay)</li>
 *   <li>Implements {@link Config#maxUnsuccessfulResponses()}</li>
 * </ol>
 * 
 * All events are emitted serially.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ResponsePipeline extends AbstractLocalEventEmitter
{
    /**
     * Sent when a response has been successfully written on the wire.<p>
     * 
     * Only one attachment is provided, the {@code Response} object.<p>
     * 
     * The <i>final</i> response is the last successful emission.
     */
    enum Success { INSTANCE }
    
    /**
     * Sent on response failure.<p>
     * 
     * The first attachment will always be the error, a {@code Throwable}.<p>
     * 
     * The second attachment is the Response object, but only if available. If
     * the error was propagated to the pipeline from the response stage provided
     * by the client, then the second attachment is {@code null}. Errors
     * occurring after this point in time, however, will have the response
     * attachment present.<p>
     * 
     * No errors are emitted after the <i>final</i> response. Errors from
     * the client are logged but otherwise ignored. And, there's obviously no
     * pending writes from the channel that could go wrong.
     */
    enum Error { INSTANCE }
    
    private static final System.Logger LOG
            = System.getLogger(ResponsePipeline.class.getPackageName());
    
    private static final CompletionStage<Response> INIT_TIMER = completedStage(null);
    
    private static final Throwable ABORT = new Throwable();
    
    private final Config cfg;
    private final int maxUnssuccessful;
    private final HttpExchange exch;
    private final DefaultClientChannel chApi;
    private final DefaultActionRegistry actions;
    private final Deque<CompletionStage<Response>> queue;
    private final SeriallyRunnable op;
    
    /**
     * Constructs a {@code ResponsePipeline}.<p>
     * 
     * @param exch the HTTP exchange
     * @param chApi channel's delegate used for writing
     * @param actions registry used to lookup after-actions
     */
    ResponsePipeline(HttpExchange exch, DefaultClientChannel chApi, DefaultActionRegistry actions) {
        this.cfg = chApi.getServer().getConfig();
        this.maxUnssuccessful = cfg.maxUnsuccessfulResponses();
        this.chApi = chApi;
        this.actions = actions;
        this.exch = exch;
        this.queue = new ConcurrentLinkedDeque<>();
        this.op = new SeriallyRunnable(this::pollAndProcessAsync, true);
    }
    
    /**
     * Start the timer that will result in a {@link ResponseTimeoutException} on
     * delay from the application to deliver response objects to the channel.<p>
     * 
     * This timer is started by the HTTP exchange once the request body has been
     * consumed (or immediately if body is empty). Up until that point and for
     * as long as progress is being made on the request-side, the application is
     * free to take forever to yield responses.
     */
    // Note: The response body timer is set by method subscribeToResponse().
    void startTimeout() {
        // No need to addFirst/preempt; timer only active whilst waiting
        add(INIT_TIMER);
    }
    
    void add(CompletionStage<Response> resp) {
        queue.add(resp);
        op.run();
    }
    
    void addFirst(CompletionStage<Response> resp) {
        queue.addFirst(resp);
        op.run();
    }
    
    // Except for <timedOut>, all other fields in this class are accessed solely
    // from within the serialized operation; no need for volatile. "timedOut =
    // true" (see timeoutAction()) follows by a re-run, i.e. is safe to do by
    // the timer's scheduling thread even without volatile (see JavaDoc of
    // SeriallyRunnable).
    private Timeout timer;
    private boolean timedOut;
    private boolean timeoutEmitted;
    private List<ResourceMatch<AfterAction>> matched;
    
    private void timeoutAction() {
        timedOut = true;
        op.run();
    }
    
    private static void scheduleClose(DefaultClientChannel chApi) {
        Timeout.schedule(SECONDS.toNanos(5), () -> {
            if (chApi.isAnythingOpen()) {
                LOG.log(WARNING, "Response timed out, but after 5 seconds more the channel is still not closed. Closing child.");
                chApi.closeSafe();
            }
        });
    }
    
    private void pollAndProcessAsync() {
        if (timedOut && !timeoutEmitted) {
            timeoutEmitted = true;
            if (chApi.isOpenForWriting()) {
                scheduleClose(chApi);
                var thr = new ResponseTimeoutException("Gave up waiting on a response.");
                emit(Error.INSTANCE, thr, null);
            } else {
                LOG.log(DEBUG,
                    "Will not emit response timeout; channel closed for writing " +
                    "- so, we were in effect not waiting.");
            }
        }
        
        CompletionStage<Response> stage = queue.poll();
        if (stage == null) {
            op.complete();
            return;
        }
        
        if (stage == INIT_TIMER) {
            if (timer == null && !wroteFinal) {
                timer = new Timeout(cfg.timeoutIdleConnection());
                timer.schedule(this::timeoutAction);
            }
            op.complete();
            return;
        } else if (timer != null && !wroteFinal) {
            // Going to process response; timer active only while waiting
            timer.abort();
        }
        
        var req = exch.getSkeletonRequest();
        if (req == null) {
            LOG.log(DEBUG, "No valid request available; will not run after-actions.");
        } else {
            if (matched == null) {
                matched = actions.lookupAfter(req.target());
            }
            for (ResourceMatch<AfterAction> m : matched) {
                stage = stage.thenCompose(rsp ->
                        m.get().apply(new DefaultRequest(exch.getHttpVersion(), req, m), rsp));
            }
        }
        
        stage.thenApply(this::closeHttp1_0)
             .thenApply(this::handleUnknownLength)
             .thenApply(this::trackConnectionClose)
             .thenApply(this::trackUnsuccessful)
             .thenCompose(this::subscribeToResponse)
             .whenComplete(this::handleResult);
    }
    
    private Response inFlight = null;
    private boolean wroteFinal = false;
    private boolean sawConnectionClose = false;
    private int n100continue = 0;
    
    private Response closeHttp1_0(Response rsp) {
        // No support for HTTP 1.0 Keep-Alive
        if (rsp.isFinal() &&
            exch.getHttpVersion().isLessThan(HTTP_1_1) &&
            !hasConnectionClose(rsp))
        {
            return setConnectionClose(rsp);
        }
        return rsp;
    }
    
    private Response handleUnknownLength(Response rsp) {
        // Two quick reads; assume "Connection: close" will be present
        if (!rsp.mustShutdownOutputAfterWrite()         &&
            !rsp.mustCloseAfterWrite()                  &&
            // If not, we need to dig a little bit
             rsp.headerIsMissingOrEmpty(CONTENT_LENGTH) &&
            !hasConnectionClose(rsp)                    &&
            !rsp.isBodyEmpty())
        {
            // TODO: In the future when implemented, chunked encoding may also be an option
            LOG.log(DEBUG, "Response body of unknown length and not marked to close connection, setting \"Connection: close\".");
            return setConnectionClose(rsp);
        }
        return rsp;
    }
    
    private Response trackConnectionClose(Response in) {
        final Response out;
        
        if (sawConnectionClose) {
            if (in.isFinal() && !hasConnectionClose(in)) {
                // Flag propagates to response
                out = setConnectionClose(in);
            } else {
                // Message not final or already has the header = NOP
                out = in;
            }
        } else if (hasConnectionClose(in)) {
            // Update flag, but no need to modify response
            sawConnectionClose = true;
            out = in;
        } else if (in.isFinal() &&
                  (exch.getRequestHead() != null && hasConnectionClose(exch.getRequestHead()) ||
                  !chApi.isOpenForReading()))
        {
            // Flag also propagates from request or current half-closed state of channel
            sawConnectionClose = true;
            out = setConnectionClose(in);
        } else {
            // Haven't seen the header before and no current state indicates we need it = NOP
            out = in;
        }
        
        return out;
    }
    
    private Response trackUnsuccessful(final Response in) {
        final Response out;
        if (isClientError(in.statusCode()) || isServerError(in.statusCode())) {
            // Bump error counter
            int n = chApi.attributes().<Integer>asMapAny()
                    .merge("alpha.nomagichttp.responsepipeline.nUnssuccessful", 1, Integer::sum);
            
            if (n >= maxUnssuccessful && !in.mustCloseAfterWrite()) {
                LOG.log(DEBUG, "Max number of unsuccessful responses reached. Marking response to close client channel.");
                out = in.toBuilder().mustCloseAfterWrite(true).build();
            } else {
                out = in;
            }
        } else {
            // Reset
            chApi.attributes().set("alpha.nomagichttp.responsepipeline.nUnssuccessful", 0);
            out = in;
        }
        return out;
    }
    
    private CompletionStage<Long> subscribeToResponse(Response rsp) {
        if (wroteFinal) {
            LOG.log(WARNING, () -> "HTTP exchange not active. This response is ignored: " + rsp);
            return failedStage(ABORT);
        }
        if (rsp.isInformational()) {
            if (exch.getHttpVersion().isLessThan(HTTP_1_1)) {
                throw new ResponseRejectedException(rsp, PROTOCOL_NOT_SUPPORTED,
                        exch.getHttpVersion() + " does not support 1XX (Informational) responses.");
            }
            if (rsp.statusCode() == ONE_HUNDRED && ++n100continue > 1) {
                LOG.log(n100continue == 2 ? DEBUG : WARNING, "Ignoring repeated 100 (Continue).");
                return failedStage(ABORT);
            }
        }
        
        LOG.log(DEBUG, () -> "Subscribing to response: " + rsp);
        inFlight = rsp;
        wroteFinal = rsp.isFinal();
        
        // Response.body() (app) -> operator -> ResponseBodySubscriber (server)
        // On timeout, operator will cancel upstream and error out downstream.
        var body = new TimeoutOp.Pub<>(true, false, rsp.body(), cfg.timeoutIdleConnection(), () -> {
            scheduleClose(chApi);
            return new ResponseTimeoutException(
                    "Gave up waiting on a response body bytebuffer.");
        });
        var sub = new ResponseBodySubscriber(rsp, exch, chApi);
        body.subscribe(sub);
        return sub.asCompletionStage();
    }
    
    private void handleResult(/* This: */ Long bytesWritten, /* Or: */ Throwable thr) {
        Response rsp = inFlight;
        inFlight = null;
        
        if (!wroteFinal && timer != null) {
            timer.reschedule(this::timeoutAction);
        }
        
        if (thr != null && thr.getCause() == ABORT) {
            op.complete();
            op.run();
            return;
        }
        
        tryActOnChannelCommands(rsp);
        if (bytesWritten != null) {
            actOnWriteSuccess(bytesWritten, rsp);
        } else {
            assert thr != null;
            actOnWriteFailure(thr, rsp);
        }
        
        op.complete();
        op.run();
    }
    
    private void tryActOnChannelCommands(Response rsp) {
        if (rsp == null) {
            // Application's provided stage completed exceptionally, nothing to do
            return;
        }
        if (rsp.mustCloseAfterWrite()) {
            LOG.log(DEBUG, "Response wants us to close the child, will close.");
            chApi.closeSafe();
        } else if (rsp.mustShutdownOutputAfterWrite()) {
            LOG.log(DEBUG, "Response wants us to shutdown output, will shutdown.");
            chApi.shutdownOutputSafe();
            // DefaultServer will not start a new exchange
        }
    }
    
    private void actOnWriteSuccess(Long bytesWritten, Response rsp) {
        LOG.log(DEBUG, () -> "Sent response (" + bytesWritten + " bytes).");
        if (wroteFinal && sawConnectionClose && chApi.isOpenForWriting()) {
            LOG.log(DEBUG, "Saw \"Connection: close\", shutting down output.");
            chApi.shutdownOutputSafe();
        }
        emit(Success.INSTANCE, rsp, null);
    }
    
    private void actOnWriteFailure(Throwable thr, Response rsp) {
        if (rsp == null) {
            // thr originates from application
            if (wroteFinal) {
                LOG.log(ERROR,
                    "Application's response stage completed exceptionally, " +
                    "but HTTP exchange is not active. This error does not propagate anywhere.", thr);
                // no emission
            } else {
                emit(Error.INSTANCE, thr, null);
            }
        } else {
            // thr originates from response writer
            if (chApi.isOpenForWriting()) {
                // and no bytes were written on the wire, rollback
                wroteFinal = false;
            }
            emit(Error.INSTANCE, thr, rsp);
        }
    }
    
    private void emit(Enum<?> event, Object att1, Object att2) {
        int n = super.emit(event, att1, att2);
        if (n > 0) {
            return;
        }
        if (event == Success.INSTANCE) {
            LOG.log(DEBUG, "Successfully wrote a response, but no listener consumed the result.");
        } else {
            assert event == Error.INSTANCE;
            LOG.log(WARNING,
                "Response stage or response writing failed, " +
                "but no listener consumed this error.", (Throwable) att1);
        }
    }
    
    private static boolean hasConnectionClose(HeaderHolder msg) {
        return msg.headerContains(CONNECTION, "close");
    }
    
    private static Response setConnectionClose(Response rsp) {
        return rsp.toBuilder().header(CONNECTION, "close").build();
    }
}