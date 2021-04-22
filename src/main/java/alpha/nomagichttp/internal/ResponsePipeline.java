package alpha.nomagichttp.internal;

import alpha.nomagichttp.handler.ResponseRejectedException;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.util.SeriallyRunnable;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Flow;

import static alpha.nomagichttp.HttpConstants.StatusCode.ONE_HUNDRED;
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
        this.chan  = requireNonNull(chan);
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
        r.thenCompose(this::subscribeToResponse)
         .whenComplete(this::handleChannelResult);
    }
    
    private Response inFlight = null;
    private boolean wroteFinal = false;
    private int n100continue = 0;
    
    private CompletionStage<ResponseBodySubscriber.Result> subscribeToResponse(Response r) {
        if (wroteFinal) {
            throw new ResponseRejectedException(r, EXCHANGE_NOT_ACTIVE,
                    "Final response already written.");
        }
        if (r.isInformational()) {
            if (exch.getHttpVersion().isLessThan(HTTP_1_1)) {
                throw new ResponseRejectedException(r, PROTOCOL_NOT_SUPPORTED,
                        exch.getHttpVersion() + " does not support 1XX (Informational) responses.");
            }
            if (r.statusCode() == ONE_HUNDRED && ++n100continue > 1) {
                LOG.log(n100continue == 2 ? DEBUG : WARNING, "Ignoring repeated 100 (Continue).");
                return failedStage(new AssertionError("Ignored"));
            }
        }
        inFlight = r;
        wroteFinal = r.isFinal();
        LOG.log(DEBUG, () -> "Subscribing to response: " + r);
        var rbs = new ResponseBodySubscriber(r, exch, chan);
        r.body().subscribe(rbs);
        return rbs.asCompletionStage();
    }
    
    private void handleChannelResult(ResponseBodySubscriber.Result res, Throwable thr) {
        if (n100continue > 1) {
            return;
        }
        Response r = inFlight;
        inFlight = null;
        if (res != null) {
            // Success
            LOG.log(DEBUG, () -> "Sent response (" + res.bytesWritten() + " bytes).");
            // TODO: Implement "mayAbortRequest" flag
            if (r.mustCloseAfterWrite()) {
                LOG.log(DEBUG, "Response wants us to close the child, will close.");
                chan.closeSafe();
            }
            publish(r, res.bytesWritten(), null);
        } else {
            // Failed
            if (chan.isOpenForWriting()) {
                // and no bytes were written on the wire
                wroteFinal = false;
            }
            assert thr != null;
            publish(r, null, thr);
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