package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.ResponseRejectedException;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.util.SeriallyRunnable;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Flow;

import static alpha.nomagichttp.HttpConstants.HeaderKey.CONNECTION;
import static alpha.nomagichttp.HttpConstants.HeaderKey.CONTENT_LENGTH;
import static alpha.nomagichttp.HttpConstants.StatusCode.ONE_HUNDRED;
import static alpha.nomagichttp.HttpConstants.StatusCode.isClientError;
import static alpha.nomagichttp.HttpConstants.StatusCode.isServerError;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.handler.ResponseRejectedException.Reason.EXCHANGE_NOT_ACTIVE;
import static alpha.nomagichttp.handler.ResponseRejectedException.Reason.PROTOCOL_NOT_SUPPORTED;
import static alpha.nomagichttp.util.Subscriptions.noop;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.failedStage;

/**
 * Enqueues responses and schedules them to be written out on a client
 * channel.<p>
 * 
 * The {@code write} methods of {@link DefaultClientChannel} is a direct facade
 * for the {@code add} method declared in this class.<p>
 * 
 * For each response completed, the result is published to all active
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
 *   <li>The subscription will never cancel and the subscriber implicitly
 *       requests {@code Long.MAX_VALUE}. In fact, the subscription object
 *        passed to the subscriber is NOP. I.e. there is no backpressure
 *        control and the subscription only terminates when the pipeline
 *       terminates.</li>
 *   <li>Subscriber identity is not tracked. Reuse equals duplication.</li>
 *   <li>The behavior is undefined if the subscriber throws an exception.</li>
 * </ol>
 * 
 * After the final response has completed (successfully), all active
 * subscriptions will be completed (this is the perfect opportunity to trigger a
 * new HTTP exchange).<p>
 * 
 * The subscription of this class is never signaled {@code onError}. Failures
 * from the accepted {@code CompletionStage<Response>} and failures
 * from the underlying {@link ResponseBodySubscriber#asCompletionStage()} is
 * published as-is boxed in a {@code Result} item to the subscribers of this
 * class.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ResponsePipeline implements Flow.Publisher<ResponsePipeline.Result>
{
    interface Result {
        /**
         * Returns the response that was transmitted or attempted to transmit.
         * 
         * @return the response (never {@code null}
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
         * Returns the error if response-writing failed, otherwise {@code null}.
         * 
         * @return the error if response-writing failed, otherwise {@code null}
         */
        Throwable error();
    }
    
    private static final System.Logger LOG
            = System.getLogger(ResponsePipeline.class.getPackageName());
    
    private static final Throwable IGNORE = new AssertionError();
    
    private final int maxUnssuccessful;
    private final HttpExchange exch;
    private final DefaultClientChannel chan;
    private final Deque<CompletionStage<Response>> queue;
    private final SeriallyRunnable op;
    private final List<Flow.Subscriber<? super Result>> subs;
    
    /**
     * Constructs a {@code ResponsePipeline}.<p>
     * 
     * @param exch the HTTP exchange
     * @param chan channel's delegate used for writing
     * 
     * @throws NullPointerException if any arg is {@code null}
     */
    ResponsePipeline(HttpExchange exch, DefaultClientChannel chan) {
        this.maxUnssuccessful = chan.getServer().getConfig().maxUnsuccessfulResponses();
        this.chan  = chan;
        this.exch  = requireNonNull(exch);
        this.queue = new ConcurrentLinkedDeque<>();
        this.op    = new SeriallyRunnable(this::pollAndProcessAsync, true);
        this.subs  = new ArrayList<>();
    }
    
    @Override
    public void subscribe(Flow.Subscriber<? super Result> s) {
        s.onSubscribe(noop());
        subs.add(s);
    }
    
    void add(CompletionStage<Response> resp) {
        requireNonNull(resp);
        queue.add(resp);
        op.run();
    }
    
    void addFirst(CompletionStage<Response> resp) {
        requireNonNull(resp);
        queue.addFirst(resp);
        op.run();
    }
    
    private void pollAndProcessAsync() {
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
         .whenComplete(this::handleChannelResult);
    }
    
    // HttpExchange memory; will go out of scope after final response
    private Response inFlight = null;
    private boolean wroteFinal = false;
    private boolean sawConnectionClose = false;
    private int n100continue = 0;
    
    private Response closeHttp1_0(Response rsp) {
            // 1XX (Informational) will be ignored for HTTP 1.0 clients
        if (rsp.isFinal() &&
            // No support for HTTP 1.0 Keep-Alive
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
            throw new ResponseRejectedException(rsp, EXCHANGE_NOT_ACTIVE,
                    "Final response already written.");
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
        
        if (LOG.isLoggable(DEBUG)) {
            LOG.log(DEBUG, "Subscribing to response: " + rsp);
        }
        inFlight = rsp;
        wroteFinal = rsp.isFinal();
        var rbs = new ResponseBodySubscriber(rsp, exch, chan);
        rsp.body().subscribe(rbs);
        return rbs.asCompletionStage();
    }
    
    private void handleChannelResult(ResponseBodySubscriber.Result res, Throwable thr) {
        Response rsp = inFlight;
        inFlight = null;
        
        if (thr != null && thr.getCause() == IGNORE) {
            op.complete();
            op.run();
            return;
        }
        
        // Response is null if application's provided stage completed exceptionally
        if (rsp != null) {
            if (rsp.mustCloseAfterWrite()) {
                LOG.log(DEBUG, "Response wants us to close the child, will close.");
                chan.closeSafe();
            } else if (rsp.mustShutdownOutputAfterWrite()) {
                LOG.log(DEBUG, "Response wants us to shutdown output, will shutdown.");
                chan.shutdownOutputSafe();
                // DefaultServer will not start a new exchange
            }
        }
        
        if (res != null) {
            // Success
            assert rsp != null;
            LOG.log(DEBUG, () -> "Sent response (" + res.bytesWritten() + " bytes).");
            if (wroteFinal && sawConnectionClose && chan.isOpenForWriting()) {
                LOG.log(DEBUG, "Saw \"Connection: close\", shutting down output.");
                chan.shutdownOutputSafe();
            }
            publish(rsp, res.bytesWritten(), null);
        } else {
            // Failed
            assert thr != null;
            if (chan.isOpenForWriting()) {
                // and so no bytes were written on the wire
                wroteFinal = false;
            }
            publish(rsp, null, thr);
        }
        
        op.complete();
        op.run();
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