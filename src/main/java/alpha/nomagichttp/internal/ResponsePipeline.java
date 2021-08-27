package alpha.nomagichttp.internal;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.action.AfterAction;
import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.handler.ResponseRejectedException;
import alpha.nomagichttp.message.HeaderHolder;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.ResponseTimeoutException;
import alpha.nomagichttp.util.SeriallyRunnable;

import java.nio.channels.InterruptedByTimeoutException;
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
import static alpha.nomagichttp.internal.DefaultActionRegistry.Match;
import static alpha.nomagichttp.message.Responses.continue_;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;
import static java.util.concurrent.CompletableFuture.completedStage;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Enqueues responses and schedules them to be written out on a client
 * channel.<p>
 * 
 * The {@code write} methods of {@link DefaultClientChannel} is a direct facade
 * for the {@code add} methods declared in this class.<p>
 * 
 * The life of a pipeline is scoped to the HTTP exchange and not to the channel.
 * The final response is the last response accepted by the pipeline. The channel
 * will be updated with a new delegate pipeline instance at the start of each
 * new HTTP exchange.<p>
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
 *   <li>Emits the high-level {@link ResponseTimeoutException} on response
 *       enqueuing - and response body emission delay</li>
 *   <li>Also emits low-level exceptions from the underlying channel
 *       implementation (such as {@link InterruptedByTimeoutException}.</li>
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
     * No more emissions will occur after the final response.
     */
    enum Success { INSTANCE }
    
    /**
     * Sent on timeout or response failure.<p>
     * 
     * The first attachment will always be the error, a {@code Throwable}.<p>
     * 
     * The second attachment is the Response object, but only if available. The
     * response will always be available after the response stage has completed
     * normally. And so, if the response is available, the origin of the error
     * is most likely the underlying channel, for instance trying to write a
     * response to a closed channel.<p>
     * 
     * If the response is not available, then the error comes straight from the
     * application-provided stage itself, or it is a {@link
     * ResponseTimeoutException}.<p>
     * 
     * No errors are emitted after the <i>final</i> response. Errors from
     * the application are logged but otherwise ignored. And, there's obviously
     * no pending writes from the channel that could go wrong. And, the timer
     * that drives the timeout exception is only active whilst the pipeline is
     * waiting for a response.
     */
    enum Error { INSTANCE }
    
    /**
     * Sentinel response objects which in effect are a command for the pipeline
     * to execute.
     */
    static final class Command {
        private Command() {
            // Empty
        }
        
        /**
         * Self-schedule a 100 (Continue) response, but only if:
         * <ol>
         *   <li>The final response has not been sent, and</li>
         *   <li>a 100 (Continue) response has not been sent, and</li>
         *   <li>there is no pending ready-built 100 (Continue) response in the
         *       queue.</li>
         * </ol>
         * 
         * There is no guarantee that the application will not attempt to send
         * such a response in the future or has already provided a {@code
         * CompletionStage} of such a response albeit not yet completed. For
         * this reason alone, {@link ClientChannel#write(Response)} documents
         * that an extra 100 (Continue) is silently ignored, only three or more
         * attempts to write the continue-response will yield a warning in the
         * log.
         */
        static final CompletionStage<Response>
                TRY_SCHEDULE_100CONTINUE = completedStage(null);
        
        /**
         * Initialize the timer that will result in a {@link
         * ResponseTimeoutException} on delay from the application to deliver
         * responses to the channel.<p>
         * 
         * This timer is initialized by the HTTP exchange once the request body
         * has been consumed (or immediately if body is empty). Up until that
         * point and for as long as progress is being made on the request-side,
         * the application is free to take forever yielding responses.<p>
         * 
         * There is no need to addFirst/preempt other responses; the timer is
         * only active whilst waiting on a response. So if we have responses in
         * the queue already, great.
         */
        static final CompletionStage<Response>
                INIT_RESPONSE_TIMER = completedStage(null);
    }
    
    private static final System.Logger LOG
            = System.getLogger(ResponsePipeline.class.getPackageName());
    
    private final HttpExchange exch;
    private final DefaultClientChannel chApi;
    private final DefaultActionRegistry actions;
    private final Config cfg;
    private final int maxUnssuccessful;
    private final Deque<CompletionStage<Response>> queue;
    private final SeriallyRunnable op;
    
    /**
     * Constructs a {@code ResponsePipeline}.<p>
     * 
     * @param exch the HTTP exchange
     * @param chApi channel's delegate used for writing
     * @param actions registry used to lookup after-actions
     */
    ResponsePipeline(
            HttpExchange exch,
            DefaultClientChannel chApi,
            DefaultActionRegistry actions)
    {
        this.exch = exch;
        this.chApi = chApi;
        this.actions = actions;
        this.cfg = chApi.getServer().getConfig();
        this.maxUnssuccessful = cfg.maxUnsuccessfulResponses();
        this.queue = new ConcurrentLinkedDeque<>();
        this.op = new SeriallyRunnable(this::pollAndProcessAsync, true);
    }
    
    void add(CompletionStage<Response> resp) {
        queue.add(resp);
        op.run();
    }
    
    void addFirst(CompletionStage<Response> resp) {
        queue.addFirst(resp);
        op.run();
    }
    
    // Except for <timedOut>, all other [non-final] fields in this class are
    // accessed solely from within the serialized operation; no need for
    // volatile. "timedOut = true" (see timeoutAction()) follows by a re-run,
    // i.e. is safe to do by the timer's scheduling thread even without volatile
    // (see JavaDoc of SeriallyRunnable).
    
    private Timeout timer;
    private boolean timedOut;
    
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
        actOnTimerTimeout();
        
        // Wait waaat! Why do we continue even after a possible timeout?
        // Coz ResponseTimeoutException doesn't immediately close the channel or
        // render the channel/pipeline useless. In fact, application (or our
        // very own error handler) is supposed to be able to produce (and send!)
        // an alternative response. For that we need an operating pipeline lol 
        
        var stage = queue.poll();
        if (stage == null) {
            op.complete();
            return;
        }
        
        if (actOnCommand(stage)) {
            op.complete();
            op.run();
            return;
        }
        
        if (timer != null) {
            // Going to process response; timer active only while waiting
            timer.abort();
        }
        
        stage.thenApply   (this::acceptRejectOrAbort)
             .thenCompose (this::invokeAfterActions)
             .thenApply   (this::closeHttp1_0)
             .thenApply   (this::fixUnknownLength)
             .thenApply   (this::trackConnectionClose)
             .thenApply   (this::trackUnsuccessful)
             .thenCompose (this::subscribeToResponse)
             .whenComplete(this::handleResult); // <-- this re-schedules the timer
    }
    
    private boolean timeoutEmitted;
    
    private void actOnTimerTimeout() {
        if (!timedOut || timeoutEmitted) {
            return;
        }
        assert !wroteFinal : "No timer was supposed to be active after final response.";
        timeoutEmitted = true;
        if (chApi.isOpenForWriting()) {
            scheduleClose(chApi);
            var thr = new ResponseTimeoutException("Gave up waiting on a response.");
            emit(Error.INSTANCE, thr, null);
        } else {
            LOG.log(DEBUG,
                "Will not emit response timeout; channel closed for writing " +
                "- so, we were in effect not waiting for a response.");
        }
    }
    
    private boolean actOnCommand(CompletionStage<Response> stage) {
        if (stage == Command.TRY_SCHEDULE_100CONTINUE) {
            if (!sentOrPending100Continue()) {
                add(continue_().completedStage());
            }
            return true;
        }
        if (stage == Command.INIT_RESPONSE_TIMER) {
            if (timer == null && !wroteFinal) {
                // Note: The response body timer is set by method subscribeToResponse().
                timer = new Timeout(cfg.timeoutIdleConnection());
                timer.schedule(this::timeoutAction);
            }
            return true;
        }
        return false;
    }
    
    private boolean wroteFinal; // <-- set on subscription to final
    private int n100continue;
    
    private boolean sentOrPending100Continue() {
        if (wroteFinal || n100continue > 0) {
            return true;
        }
        for (var stage : queue) {
            Response rsp;
            try {
                rsp = stage.toCompletableFuture().getNow(null);
            } catch (Exception e) {
                // Not supported or completion error
                continue;
            }
            if (rsp != null && rsp.statusCode() == ONE_HUNDRED) {
                return true;
            }
        }
        return false;
    }
    
    private static final RuntimeException
            // Response processing aborted (response ignored)
            ABORT = new RuntimeException();
    
    private Response acceptRejectOrAbort(Response rsp) {
        if (wroteFinal) {
            LOG.log(WARNING, () -> "HTTP exchange not active. This response is ignored: " + rsp);
            throw ABORT;
        }
        if (rsp.isInformational()) {
            if (exch.getHttpVersion().isLessThan(HTTP_1_1)) {
                throw new ResponseRejectedException(rsp, PROTOCOL_NOT_SUPPORTED,
                        exch.getHttpVersion() + " does not support 1XX (Informational) responses.");
            }
            if (rsp.statusCode() == ONE_HUNDRED && ++n100continue > 1) {
                LOG.log(n100continue == 2 ? DEBUG : WARNING, "Ignoring repeated 100 (Continue).");
                throw ABORT;
            }
        }
        return rsp;
    }
    
    private List<Match<AfterAction>> matched;
    
    private CompletionStage<Response> invokeAfterActions(Response rsp) {
        var res = rsp.completedStage();
        var req = exch.getSkeletonRequest();
        if (req == null) {
            LOG.log(DEBUG, "No valid request available; will not run after-actions.");
            // return <res>
        } else {
            if (matched == null) {
                matched = actions.lookupAfter(req.target());
            }
            for (Match<AfterAction> m : matched) {
                res = res.thenCompose(r -> m.action().apply(
                        new DefaultRequest(exch.getHttpVersion(), req, m.segments()), r));
            }
        }
        return res;
    }
    
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
    
    private Response fixUnknownLength(Response rsp) {
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
    
    private boolean sawConnectionClose;
    
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
    
    private Response inFlight;
    
    private CompletionStage<Long> subscribeToResponse(Response rsp) {
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
        
        tryRescheduleTimer();
        
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
    
    private void tryRescheduleTimer() {
        // No need to re-schedule if
        //   we're not going to wait for more responses (wrote final), or
        //   timer haven't been started yet (timer is null), or
        //   already timed out (will always end with channel closure)
        if (!wroteFinal && timer != null && !timedOut) {
            timer.reschedule(this::timeoutAction);
        }
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
                tryRescheduleTimer();
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