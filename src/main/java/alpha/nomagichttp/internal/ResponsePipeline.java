package alpha.nomagichttp.internal;

import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.message.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

import static alpha.nomagichttp.util.Subscriptions.noop;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;
import static java.util.Objects.requireNonNull;

/**
 * Enqueues responses and schedules them to be written out on a client
 * channel.<p>
 * 
 * The {@code write} methods of {@link DefaultClientChannel} is a direct facade
 * for the {@code add} method declared in this class. <strong>Note in particular
 * that currently, only one response is allowed to be written no matter its
 * status code.</strong> This will change in the near future.<p>
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
 * The subscription never terminates through error and there is no embedded
 * open/closed state in this class. If more than one response have been written
 * or an attempt is made to write after a previous corrupt message, both cases
 * will be rejected on the calling thread (this is wrong, can't have the same
 * error type be both sync and async). A "closed" state will likely be
 * implemented in the future when multiple responses are supported.<p>
 * 
 * After the final response has completed (successfully), all active
 * subscriptions will be completed (this is the perfect opportunity to trigger a
 * new HTTP exchange).<p>
 * 
 * Failures from the accepted {@code CompletionStage<Response>} and failures
 * from the underlying {@link ResponseBodySubscriber#asCompletionStage()} is
 * published as-is to the subscribers of this class.<p>
 * 
 * Writes may be scheduled even if the underlying channel is closed and this
 * won't throw an exception on the call site, the write-failure will propagate
 * as a throwable through the published result instead. The write/add methods
 * are defined to be asynchronous (TODO: This needs to go to the public JavaDoc
 * instead on ClientChannel, plus, the throwable needs to be specified).
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ResponsePipeline implements Flow.Publisher<ResponsePipeline.Result>
{
    interface Result {
        /**
         * Returns the number of bytes written (response length), only if
         * response completed successfully, otherwise {@code null}.
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
    private final AtomicBoolean open; // <-- in future, replace with collection (?)
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
        this.chan = requireNonNull(chan);
        this.exch = requireNonNull(exch);
        this.open = new AtomicBoolean(true);
        this.subs = new ArrayList<>();
    }
    
    void add(CompletionStage<Response> resp) {
        requireNonNull(resp);
        if (!open.compareAndSet(true, false)) {
            throw new IllegalStateException(
                    "Response already in-flight or bytes written during HTTP exchange.");
        }
        resp.whenComplete((r, t) -> {
            if (t == null) {
                safe(() -> initiate(r));
            } else {
                open.set(true);
                publish(null, t);
            }
        });
    }
    
    @Override
    public void subscribe(Flow.Subscriber<? super Result> s) {
        s.onSubscribe(noop());
        subs.add(s);
    }
    
    private void safe(Runnable code) {
        try {
            code.run();
        } catch (Exception e) { // <-- Error is best not managed by us
            // unexpected, let <open> remain false
            publish(null, e);
        }
    }
    
    private void publish(Long len, Throwable thr) {
        Result r = new Result() {
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
    
    private void initiate(Response resp) {
        LOG.log(DEBUG, () -> "Subscribing to response: " + resp);
        ResponseBodySubscriber rbs = new ResponseBodySubscriber(resp, exch, chan);
        resp.body().subscribe(rbs);
        
        rbs.asCompletionStage().whenComplete((len, thr) -> {
            if (len != null) {
                // TODO: Implement "mayAbortRequest" flag
                if (resp.mustCloseAfterWrite()) {
                    LOG.log(DEBUG, "Response wants us to close the child, will close.");
                    chan.closeSafe();
                }
                // <open> remains false; only 1 response supported
            } else {
                open.set(chan.isOpenForWriting()); // <-- likely determined by ResponseBodySubscriber
            }
            publish(len, thr);
        });
    }
}