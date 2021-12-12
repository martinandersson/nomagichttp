package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.BadRequestException;
import alpha.nomagichttp.message.ContentHeaders;
import alpha.nomagichttp.message.DefaultContentHeaders;
import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.RequestBodyTimeoutException;
import alpha.nomagichttp.util.Publishers;

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

import static alpha.nomagichttp.HttpConstants.HeaderKey.CONTENT_LENGTH;
import static alpha.nomagichttp.HttpConstants.HeaderKey.TRANSFER_ENCODING;
import static alpha.nomagichttp.internal.AtomicReferences.lazyInit;
import static alpha.nomagichttp.internal.SubscriptionMonitoringOp.alreadyCompleted;
import static alpha.nomagichttp.internal.SubscriptionMonitoringOp.subscribeTo;
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
     * @param chApi extended channel API (used for exceptional closure)
     * @param timeout duration (may be {@code null})
     * @param beforeNonEmptyBodySubscription before callback (may be {@code null})
     * 
     * @return a request body
     * @throws NullPointerException if any required argument is {@code null}
     * @throws BadRequestException on invalid message framing
     */
    static RequestBody of(
            DefaultContentHeaders headers,
            Flow.Publisher<DefaultPooledByteBufferHolder> chIn,
            DefaultClientChannel chApi,
            Duration timeout,
            Runnable beforeNonEmptyBodySubscription)
    {
        final Flow.Publisher<DefaultPooledByteBufferHolder> content;
        
        var cl = headers.contentLength();
        var te = headers.transferEncoding();
        
        if (cl.isPresent()) {
            if (!te.isEmpty()) {
                throw new BadRequestException(
                    CONTENT_LENGTH + " and " + TRANSFER_ENCODING + " present.");
            }
            long len = cl.getAsLong();
            if (len == 0) {
                return empty(chIn, chApi, headers);
            }
            assert len > 0;
            content = new LengthLimitedOp(len, chIn);
        } else if (te.isEmpty()) {
            // "If this is a request message [...] then"
            //  the message body length is zero (no message body is present)."
            // (only outbound responses may be close-delimited)
            // https://tools.ietf.org/html/rfc7230#section-3.3.3
            return empty(chIn, chApi, headers);
        } else {
            throw new UnsupportedOperationException("Implement");
            // Apply 1 chunked encoding, throw ex if more than that
        }
        
        return ofContent(content, timeout, chApi, headers, beforeNonEmptyBodySubscription);
    }
    
    private static RequestBody empty(
            Flow.Publisher<DefaultPooledByteBufferHolder> chIn,
            DefaultClientChannel chApi,
            DefaultContentHeaders headers)
    {
        requireNonNull(chIn);
        requireNonNull(chApi);
        return new RequestBody(headers, null, null, null);
    }
    
    private static RequestBody ofContent(
            Flow.Publisher<DefaultPooledByteBufferHolder> content,
            Duration timeout,
            DefaultClientChannel chApi,
            DefaultContentHeaders headers,
            Runnable beforeNonEmptyBodySubscription)
    {
        // Upstream is ChannelByteBufferPublisher, he can handle async cancel
        var timedOp = timeout == null ? null :
                new TimeoutOp.Flow<>(false, true, content, timeout, () -> {
                    if (LOG.isLoggable(DEBUG) && chApi.isOpenForReading()) {
                        LOG.log(DEBUG, "Request body timed out, shutting down child channel's read stream.");
                    }
                    // No new HTTP exchange
                    chApi.shutdownInputSafe();
                    return new RequestBodyTimeoutException();
                });
        
        var monitor = subscribeTo(timedOp != null ? timedOp : content);
        var onError = new OnDownstreamErrorCloseReadStream<>(monitor, chApi);
        var discard = new OnCancelDiscardOp(onError);
        if (timedOp != null) {
            timedOp.start();
        }
        return new RequestBody(
                headers,
                monitor,
                discard,
                beforeNonEmptyBodySubscription);
    }
    
    private final ContentHeaders headers;
    private final SubscriptionMonitoringOp monitor;
    private final OnCancelDiscardOp discard;
    private final Runnable beforeSubsc;

    private final AtomicReference<CompletionStage<String>> cachedText;
    
    private RequestBody(
            // Required
            ContentHeaders headers,
            // All optional (relevant only for body contents)
            SubscriptionMonitoringOp monitor,
            OnCancelDiscardOp discard,
            Runnable beforeSubsc)
    {
        this.headers     = headers;
        this.monitor     = monitor;
        this.discard     = discard;
        this.beforeSubsc = beforeSubsc;
        this.cachedText  = discard == null ? null : new AtomicReference<>(null);
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
            charset = headers.contentType()
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
            discard.subscribe(subscriber);
        }
    }
    
    @Override
    public boolean isEmpty() {
        return discard == null;
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
        discard.discardIfNoSubscriber();
    }
    
    private static <T> void copyResult(
            CompletionStage<? extends T> from, CompletableFuture<? super T> to)
    {
        from.whenComplete((val, exc) -> {
            if (exc == null) {
                to.complete(val);
            } else {
                to.completeExceptionally(exc);
            }
        });
    }
}