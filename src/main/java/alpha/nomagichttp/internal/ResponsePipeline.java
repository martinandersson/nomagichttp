package alpha.nomagichttp.internal;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.handler.ResponseRejectedException;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.ResponseTimeoutException;
import alpha.nomagichttp.util.SeriallyRunnable;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static alpha.nomagichttp.HttpConstants.HeaderKey.CONNECTION;
import static alpha.nomagichttp.HttpConstants.HeaderKey.CONTENT_LENGTH;
import static alpha.nomagichttp.HttpConstants.StatusCode.ONE_HUNDRED;
import static alpha.nomagichttp.HttpConstants.StatusCode.isClientError;
import static alpha.nomagichttp.HttpConstants.StatusCode.isServerError;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.handler.ResponseRejectedException.Reason.PROTOCOL_NOT_SUPPORTED;
import static alpha.nomagichttp.internal.AtomicReferences.ifPresent;
import static alpha.nomagichttp.internal.AtomicReferences.setIfAbsent;
import static alpha.nomagichttp.internal.AtomicReferences.take;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.failedStage;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Enqueues responses and schedules them to be written out on a client
 * channel.<p>
 * 
 * The {@code write} methods of {@link DefaultClientChannel} is a direct facade
 * for the {@code add} methods declared in this class.<p>
 * 
 * Core responsibilities:
 * <ul>
 *   <li>From a queue of responses, schedule channel write operations</li>
 *   <li>Apply response transformations such as setting "Connection: close" if
 *       no "Content-Length"</li>
 *   <li>Act on response-scheduled channel commands (must-close-after-write,
 *       "Connection: close", et cetera)</li>
 * </ul>
 * 
 * In addition:
 * <ol>
 *   <li>Implements {@link Config#maxUnsuccessfulResponses()}</li>
 * </ol>
 * 
 * The result of each attempt to transmit a response is published to all active
 * subscribers. If there are no active subscribers, a successful result is
 * logged on {@code DEBUG} level and errors are logged on {@code WARNING}.<p>
 * 
 * The pipeline life-cycle is bound to/dependent on {@code HttpExchange} who is
 * the only subscriber at the moment. For any other subscriber in the future,
 * note that as a {@code Flow.Publisher}, this class currently makes a few
 * assumptions about its usage:
 * 
 * <ol>
 *   <li>There will be no concurrent invocations of {@code subscribe()}. As a
 *       <i>publisher</i>, this class is not thread-safe. The {@code add} method
 *       is.</li>
 *   <li>The subscriber is called serially.</li>
 *   <li>The only method called on the subscriber is {@code onNext()}, passing
 *       to it a result object containing the status of a response
 *       transmission. This includes failures from the client-given  {@code
 *       CompletionStage<Response>} and failures from the underlying {@link
 *       ResponseBodySubscriber#asCompletionStage()}.</li>
 *   <li>The last emission to the subscriber will be the result of any bytes
 *       sent of a final response. This ends the pipeline's life. Subsequent
 *       responses from the application will be logged, but otherwise
 *       ignored.</li>
 *   <li>Implicitly, the subscriber requests {@code Long.MAX_VALUE}.</li>
 *   <li>Subscriber identity is not tracked. Reuse equals duplication.</li>
 *   <li>The behavior is undefined if the subscriber throws an exception.</li>
 * </ol>
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ResponsePipeline implements Flow.Publisher<ResponsePipeline.Result>
{
    interface Result {
        /**
         * Returns the response that was transmitted or attempted to transmit.
         * 
         * The response object is provided whether transmission of it was
         * successful or not. On response timeout, this method returns {@code
         * null}.
         * 
         * @return the response
         */
        Response response();
        
        /**
         * Returns the number of bytes written, only if response completed
         * successfully, otherwise {@code null}.
         * 
         * @return byte count if successful, otherwise {@code null}
         */
        Long length();
        
        /**
         * Returns an error if response-writing failed or response timed out,
         * otherwise {@code null}.
         * 
         * @return an error if response-writing failed or response timed out,
         *         otherwise {@code null}
         */
        Throwable error();
    }
    
    private static final System.Logger LOG
            = System.getLogger(ResponsePipeline.class.getPackageName());
    
    private static final Throwable IGNORE = new Throwable();
    
    private final Config cfg;
    private final int maxUnssuccessful;
    private final HttpExchange exch;
    private final DefaultClientChannel chan;
    private final Deque<CompletionStage<Response>> queue;
    private final SeriallyRunnable op;
    private final List<Flow.Subscriber<? super Result>> subs;
    // This timer times out delays from app to give us a response.
    // Response body timer is set by method subscribeToResponse().
    private final AtomicReference<Timeout> timer;
    private volatile boolean timedOut;
    private boolean timeoutPublished;
    
    /**
     * Constructs a {@code ResponsePipeline}.<p>
     * 
     * @param exch the HTTP exchange
     * @param chan channel's delegate used for writing
     * 
     * @throws NullPointerException if any arg is {@code null}
     */
    ResponsePipeline(HttpExchange exch, DefaultClientChannel chan) {
        this.cfg = chan.getServer().getConfig();
        this.maxUnssuccessful = cfg.maxUnsuccessfulResponses();
        this.chan  = chan;
        this.exch  = requireNonNull(exch);
        this.queue = new ConcurrentLinkedDeque<>();
        this.op    = new SeriallyRunnable(this::pollAndProcessAsync, true);
        this.subs  = new ArrayList<>();
        this.timer = new AtomicReference<>();
        this.timedOut = false;
        this.timeoutPublished = false;
    }
    
    @Override
    public void subscribe(Flow.Subscriber<? super Result> s) {
        subs.add(s);
    }
    
    // HttpExchange starts the timer after the request
    void startTimeout() {
        Timeout t = setIfAbsent(timer, () ->
                new Timeout(cfg.timeoutIdleConnection()));
        t.reschedule(this::timeoutAction);
    }
    
    private void stopTimeout() {
        take(timer).ifPresent(Timeout::abort);
    }
    
    void add(CompletionStage<Response> resp) {
        enqueue(resp, queue::add);
    }
    
    void addFirst(CompletionStage<Response> resp) {
        enqueue(resp, queue::addFirst);
    }
    
    private void enqueue(CompletionStage<Response> resp, Consumer<CompletionStage<Response>> sink) {
        requireNonNull(resp);
        resp.thenRun(this::timeoutReset);
        sink.accept(resp);
        op.run();
    }
    
    private void timeoutAction() {
        timedOut = true;
        op.run();
    }
    
    private void timeoutReset() {
        ifPresent(timer, t -> t.reschedule(this::timeoutAction));
    }
    
    private static void scheduleClose(DefaultClientChannel chan) {
        Timeout.schedule(SECONDS.toNanos(5), () -> {
            if (chan.isAnythingOpen()) {
                LOG.log(WARNING, "Response timed out, but after 5 seconds more the channel is still not closed. Closing child.");
                chan.closeSafe();
            }
        });
    }
    
    private void pollAndProcessAsync() {
        if (timedOut && !timeoutPublished) {
            scheduleClose(chan);
            publish(null, null, new ResponseTimeoutException(
                    "Gave up waiting on a response."));
            timeoutPublished = true;
        }
        
        CompletionStage<Response> r = queue.poll();
        if (r == null) {
            op.complete();
            return;
        }
        
        // TODO: All thenApply() will become post-actions
        r.thenApply(this::closeHttp1_0)
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
            !rsp.headerContains(CONNECTION, "close"))
        {
            return rsp.toBuilder().header(CONNECTION, "close").build();
        }
        return rsp;
    }
    
    private Response handleUnknownLength(Response rsp) {
            // Two quick reads; assume "Connection: close" will be present
        if (!rsp.mustShutdownOutputAfterWrite()         &&
            !rsp.mustCloseAfterWrite()                  &&
            // If not, we need to dig a little bit
             rsp.headerIsMissingOrEmpty(CONTENT_LENGTH) &&
            !rsp.headerContains(CONNECTION, "close")    &&
            !rsp.isBodyEmpty())
        {
            // TODO: In the future when implemented, chunked encoding may also be an option
            LOG.log(DEBUG, "Response body of unknown length and not marked to close connection, setting \"Connection: close\".");
            return rsp.toBuilder().header(CONNECTION, "close").build();
        }
        return rsp;
    }
    
    private Response trackConnectionClose(Response rsp) {
        if (rsp.headerContains(CONNECTION, "close")) {
            sawConnectionClose = true;
            // will return rsp
        } else if (sawConnectionClose && rsp.isFinal()) {
            // "Connection: close" propagates from previous response(s) even if they failed
            return rsp.toBuilder().header(CONNECTION, "close").build();
        }
        return rsp;
    }
    
    private Response trackUnsuccessful(final Response in) {
        final Response out;
        if (isClientError(in.statusCode()) || isServerError(in.statusCode())) {
            // Bump error counter
            int n = chan.attributes().<Integer>asMapAny()
                    .merge("alpha.nomagichttp.responsepipeline.nUnssuccessful", 1, Integer::sum);
            
            if (n >= maxUnssuccessful && !in.mustCloseAfterWrite()) {
                LOG.log(DEBUG, "Max number of unsuccessful responses reached. Marking response to close client channel.");
                out = in.toBuilder().mustCloseAfterWrite(true).build();
            } else {
                out = in;
            }
        } else {
            // Reset
            chan.attributes().set("alpha.nomagichttp.responsepipeline.nUnssuccessful", 0);
            out = in;
        }
        return out;
    }
    
    private CompletionStage<ResponseBodySubscriber.Result> subscribeToResponse(Response rsp) {
        if (wroteFinal) {
            LOG.log(WARNING, () -> "HTTP exchange not active. This response is ignored: " + rsp);
            return failedStage(IGNORE);
        }
        if (rsp.isInformational()) {
            if (exch.getHttpVersion().isLessThan(HTTP_1_1)) {
                throw new ResponseRejectedException(rsp, PROTOCOL_NOT_SUPPORTED,
                        exch.getHttpVersion() + " does not support 1XX (Informational) responses.");
            }
            if (rsp.statusCode() == ONE_HUNDRED && ++n100continue > 1) {
                LOG.log(n100continue == 2 ? DEBUG : WARNING, "Ignoring repeated 100 (Continue).");
                return failedStage(IGNORE);
            }
        }
        
        LOG.log(DEBUG, () -> "Subscribing to response: " + rsp);
        inFlight = rsp;
        if (rsp.isFinal()) {
            wroteFinal = true;
            stopTimeout();
        }
        
        // Response.body() (app) -> operator -> ResponseBodySubscriber (server)
        // On timeout, operator will cancel upstream and error out downstream.
        var body = new TimeoutOp.Pub<>(true, false, rsp.body(), cfg.timeoutIdleConnection(), () -> {
            scheduleClose(chan);
            return new ResponseTimeoutException(
                    "Gave up waiting on a response body bytebuffer.");
        });
        var sub = new ResponseBodySubscriber(rsp, exch, chan);
        body.subscribe(sub);
        return sub.asCompletionStage();
    }
    
    private void handleResult(ResponseBodySubscriber.Result res, Throwable thr) {
        Response rsp = inFlight;
        inFlight = null;
        
        if (thr != null && thr.getCause() == IGNORE) {
            op.complete();
            op.run();
            return;
        }
        
        tryActOnChannelCommands(rsp);
        
        if (res != null) {
            actOnWriteSuccess(res, rsp);
        } else {
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
            chan.closeSafe();
        } else if (rsp.mustShutdownOutputAfterWrite()) {
            LOG.log(DEBUG, "Response wants us to shutdown output, will shutdown.");
            chan.shutdownOutputSafe();
            // DefaultServer will not start a new exchange
        }
    }
    
    private void actOnWriteSuccess(ResponseBodySubscriber.Result res, Response rsp) {
        assert rsp != null;
        LOG.log(DEBUG, () -> "Sent response (" + res.bytesWritten() + " bytes).");
        if (wroteFinal && sawConnectionClose && chan.isOpenForWriting()) {
            LOG.log(DEBUG, "Saw \"Connection: close\", shutting down output.");
            chan.shutdownOutputSafe();
        }
        publish(rsp, res.bytesWritten(), null);
    }
    
    private void actOnWriteFailure(Throwable thr, Response rsp) {
        assert thr != null;
        if (rsp == null) {
            // thr originates from application
            if (wroteFinal) {
                LOG.log(ERROR,
                    "Application's response stage completed exceptionally, " +
                    "but HTTP exchange is not active. This error does not propagate anywhere.", thr);
                // no publish
            } else {
                publish(null, null, thr);
            }
        } else {
            // thr originates from response writer
            if (chan.isOpenForWriting()) {
                // and no bytes were written on the wire, rollback
                wroteFinal = false;
            }
            publish(rsp, null, thr);
        }
    }
    
    private void publish(Response rsp, Long len, Throwable thr) {
        Result r = new Result() {
            public Response response() {
                return rsp; }
            public Long length() {
                return len; }
            public Throwable error() {
                return thr; }
        };
        
        boolean sent = false;
        for (Flow.Subscriber<? super Result> s : subs) {
            s.onNext(r);
            sent = true;
        }
        if (sent) { return; }
        
        if (len != null) {
            LOG.log(DEBUG, () ->
                "Successfully wrote " + len + " response bytes, " +
                "but no subscriber consumed the result.");
        } else {
            LOG.log(WARNING,
                "Response stage or response writing failed, " +
                "but no subscriber consumed this error.", thr);
        }
    }
}