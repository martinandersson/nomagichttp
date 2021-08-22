package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.RequestBodyTimeoutException;
import alpha.nomagichttp.util.Publishers;

import java.net.http.HttpHeaders;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.charset.Charset;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static alpha.nomagichttp.internal.AtomicReferences.lazyInit;
import static alpha.nomagichttp.internal.SubscriptionMonitoringOp.alreadyCompleted;
import static alpha.nomagichttp.internal.SubscriptionMonitoringOp.subscribeTo;
import static alpha.nomagichttp.util.Headers.contentLength;
import static alpha.nomagichttp.util.Headers.contentType;
import static java.lang.System.Logger.Level.DEBUG;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedStage;
import static java.util.concurrent.CompletableFuture.failedStage;

/**
 * Default implementation of {@link Request.Body}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class RequestBody implements Request.Body
{
    private static final System.Logger LOG = System.getLogger(RequestBody.class.getPackageName());
    private static final CompletionStage<String> COMPLETED_EMPTY_STR = completedStage("");
    
    // Copy-pasted from AsynchronousFileChannel.NO_ATTRIBUTES
    @SuppressWarnings({"rawtypes"}) // generic array construction
    private static final FileAttribute<?>[] NO_ATTRIBUTES = new FileAttribute[0];
    
    /**
     * Construct a RequestBody.
     * 
     * The first three arguments are required.<p>
     * 
     * The {@code timeout} should always be provided, but may be skipped if the
     * call-site knows the body is empty (in which case the HTTP exchange
     * arranges for the response pipeline's timeout to start immediately) or the
     * call-site intends to immediately discard the body with no further
     * delay.<p>
     * 
     * The callback is also optional, and will be called just before this class
     * subscribes the downstream subscriber (application) to the upstream
     * publisher (channel), but only if the body has contents. Subscription to
     * an empty body will delegate to an empty dummy and never invoke the
     * callback.
     * 
     * @param headers of request
     * @param chIn reading channel
     * @param chApi extended channel API (exceptional closure)
     * @param timeout duration (may be {@code null})
     * @param beforeNonEmptyBodySubscription before callback (may be {@code null})
     * 
     * @return a request body
     * @throws NullPointerException if any required argument is {@code null}
     */
    static RequestBody of(
            HttpHeaders headers,
            Flow.Publisher<DefaultPooledByteBufferHolder> chIn,
            DefaultClientChannel chApi,
            Duration timeout,
            Runnable beforeNonEmptyBodySubscription)
    {
        // TODO: If length is not present, then body is possibly chunked.
        // https://tools.ietf.org/html/rfc7230#section-3.3.3
        
        // TODO: Server should throw BadRequestException if Content-Length is present AND Content-Encoding
        // https://tools.ietf.org/html/rfc7230#section-3.3.2
        
        // "If this is a request message and none of the above are true, then"
        //  the message body length is zero (no message body is present)."
        //  - RFC 7230 §3.3.3 Message Body Length
        final long len = contentLength(headers).orElse(0);
        
        if (len <= 0) {
            requireNonNull(chIn);
            requireNonNull(chApi);
            return new RequestBody(headers, null, null, null);
        } else {
            var bounded = new LengthLimitedOp(len, chIn);
            
            // Upstream is ChannelByteBufferPublisher, he can handle async cancel
            var timedOp = timeout == null ? null :
                    new TimeoutOp.Flow<>(false, true, bounded, timeout, () -> {
                        if (LOG.isLoggable(DEBUG) && chApi.isOpenForReading()) {
                            LOG.log(DEBUG, "Request body timed out, shutting down child channel's read stream.");
                        }
                        // No new HTTP exchange
                        chApi.shutdownInputSafe();
                        return new RequestBodyTimeoutException();
                    });
            
            var monitor = subscribeTo(timedOp != null ? timedOp : bounded);
            var onError = new OnErrorCloseReadStream<>(monitor, chApi);
            var discard = new OnCancelDiscardOp(onError);
            
            if (timedOp != null) {
                timedOp.start();
            }
            
            return new RequestBody(
                    headers,
                    discard,
                    monitor,
                    beforeNonEmptyBodySubscription);
        }
    }
    
    private final HttpHeaders headers;
    private final OnCancelDiscardOp chIn;
    private final SubscriptionMonitoringOp monitor;
    private final Runnable beforeSubsc;

    private final AtomicReference<CompletionStage<String>> cachedText;
    
    private RequestBody(
            // Required
            HttpHeaders headers,
            // All optional (relevant only for body contents)
            OnCancelDiscardOp chIn,
            SubscriptionMonitoringOp monitor,
            Runnable beforeSubsc)
    {
        this.headers     = headers;
        this.chIn        = chIn;
        this.monitor     = monitor;
        this.beforeSubsc = beforeSubsc;
        this.cachedText  = chIn == null ? null : new AtomicReference<>(null);
    }
    
    @Override
    public CompletionStage<String> toText() {
        if (isEmpty()) {
            return COMPLETED_EMPTY_STR;
        }
        return lazyInit(cachedText, CompletableFuture::new, v -> copyResult(mkText(), v));
    }
    
    private CompletionStage<String> mkText() {
        final Charset charset;
        try {
            charset = contentType(headers)
                    .filter(m -> m.type().equals("text"))
                    .map(MediaType::parameters)
                    .map(p -> p.get("charset"))
                    .map(Charset::forName)
                    .orElse(UTF_8);
        } catch (Throwable t) {
            return failedStage(t);
        }
        
        return convert((buf, count) ->
                new String(buf, 0, count, charset));
    }
    
    @Override
    public CompletionStage<Long> toFile(Path file, OpenOption... options) {
        return toFile(file, Set.of(options), NO_ATTRIBUTES);
    }
    
    @Override
    public CompletionStage<Long> toFile(
            Path file, Set<? extends OpenOption> options, FileAttribute<?>... attrs)
    {
        final Set<? extends OpenOption> opt = !options.isEmpty() ? options :
                Set.of(WRITE, CREATE_NEW);
        
        final AsynchronousFileChannel fs;
        
        try {
            // TODO: Potentially re-use server's async group
            //       (currently not possible to specify group?)
            fs = AsynchronousFileChannel.open(file, opt, null, attrs);
        } catch (Throwable t) {
            return failedStage(t);
        }
        
        FileSubscriber s = new FileSubscriber(file, fs);
        subscribe(s);
        return s.asCompletionStage();
    }
    
    @Override
    public <R> CompletionStage<R> convert(BiFunction<byte[], Integer, R> f) {
        HeapSubscriber<R> s = new HeapSubscriber<>(f);
        subscribe(s);
        return s.asCompletionStage();
    }
    
    @Override
    public void subscribe(Flow.Subscriber<? super PooledByteBufferHolder> subscriber) {
        if (isEmpty()) {
            Publishers.<PooledByteBufferHolder>empty().subscribe(subscriber);
        } else {
            if (beforeSubsc != null) {
                beforeSubsc.run();
            }
            chIn.subscribe(subscriber);
        }
    }
    
    @Override
    public boolean isEmpty() {
        return chIn == null;
    }
    
    /**
     * Returns the subscription monitor.<p>
     * 
     * If the body is empty, then {@link
     * SubscriptionMonitoringOp#alreadyCompleted()}} is returned.
     * 
     * @return the subscription monitor
     */
    SubscriptionMonitoringOp subscriptionMonitor() {
        return isEmpty() ? alreadyCompleted() : monitor;
    }
    
    /**
     * If no downstream body subscriber is active, complete downstream and
     * discard upstream.<p>
     *
     * Is NOP if body is empty or already discarding.
     */
    void discardIfNoSubscriber() {
        if (isEmpty()) {
            return;
        }
        chIn.discardIfNoSubscriber();
    }
    
    private static <T> void copyResult(CompletionStage<? extends T> from, CompletableFuture<? super T> to) {
        from.whenComplete((val, exc) -> {
            if (exc == null) {
                to.complete(val);
            } else {
                to.completeExceptionally(exc);
            }
        });
    }
}