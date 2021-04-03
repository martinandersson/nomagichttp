package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.util.Subscriptions;

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
 * Internally enqueues responses and schedules them to be written out on a
 * client channel.<p>
 * 
 * The {@code write} methods of {@link ClientChannel} is a direct facade for the
 * {@code add} method declared in this class. <strong>Note in particular that
 * currently, only one response is allowed to be written no matter its status
 * code.</strong> This will change in the near future.<p>
 * 
 * For each response completed, the result is published to all active
 * subscribers. If there are no active subscribers, a successful result is
 * logged on {@code DEBUG} level and errors are logged on {@code WARNING}.<p>
 * 
 * The {@code Flow.Publisher} implementation makes a few assumptions about its
 * usage to maximize simplicity and efficiency (no locks or volatile
 * read/writes):
 * 
 * <ol>
 *   <li>There will be no concurrent invocations of {@code subscribe()}. As a
 *       <i>publisher</i>, this class is not thread-safe. The {@code add} method
 *       is.</li>
 *   <li>The subscriber is called serially.</li>
 *   <li>The subscription will never cancel and the subscriber implicitly
 *       requests {@code Long.MAX_VALUE}. In fact, the subscription object
 *       passed to the subscriber is NOP. I.e. there is no backpressure
 *       control and the subscription only terminates when the pipeline
 *       terminates.</li>
 *   <li>Subscriber identity is not tracked. Reuse equals duplication.</li>
 *   <li>The behavior is undefined if the subscriber throws an exception.</li>
 * </ol>
 * 
 * The pipeline never terminates through error and there is no embedded
 * open/closed state. See JavaDoc of {@link ClientChannel}. Attempts to write
 * after bytes have been sent will be rejected (construct a new pipeline per
 * HTTP exchange!). So only 1 response supported. A "closed" state will likely
 * be implemented in the future when multiple responses are supported.<p>
 * 
 * After the final response has completed (successfully), all active
 * subscriptions will be completed. This is the perfect opportunity to trigger a
 * new HTTP exchange.<p>
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
    
    private final DefaultClientChannel ch;
    private final AtomicBoolean open; // <-- in future, replace with collection
    private final List<Flow.Subscriber<? super Result>> subs;
    private HttpConstants.Version ver;
    
    /**
     * Constructs a {@code ResponsePipeline}.<p>
     * 
     * Note that the HTTP version may be updated post-construction. The given
     * version is merely a default to use in responses written before the
     * actual version has been negotiated.
     * 
     * @param ch channel used for responses
     * @param ver default HTTP version
     * 
     * @throws NullPointerException if any arg is {@code null}
     */
    ResponsePipeline(DefaultClientChannel ch, HttpConstants.Version ver) {
        this.ch   = requireNonNull(ch);
        this.open = new AtomicBoolean(true);
        this.subs = new ArrayList<>();
        this.ver  = requireNonNull(ver);
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
    
    void updateVersion(HttpConstants.Version newVersion) {
        ver = newVersion;
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
        ResponseBodySubscriber rbs = new ResponseBodySubscriber(ver, resp, ch);
        resp.body().subscribe(rbs);
        
        rbs.asCompletionStage().whenComplete((len, thr) -> {
            if (len != null) {
                // TODO: Implement "mayAbortRequest" flag
                if (resp.mustCloseAfterWrite()) {
                    LOG.log(DEBUG, "Response wants us to close the child, will close.");
                    ch.closeSafe();
                }
                // <open> remains false; only 1 response supported
            } else {
                open.set(ch.isOpenForWriting()); // <-- likely determined by ResponseBodySubscriber
            }
            publish(len, thr);
        });
    }
}