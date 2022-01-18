package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.BadRequestException;
import alpha.nomagichttp.message.BetterHeaders;
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
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import static alpha.nomagichttp.HttpConstants.HeaderName.CONTENT_LENGTH;
import static alpha.nomagichttp.HttpConstants.HeaderName.TRANSFER_ENCODING;
import static alpha.nomagichttp.internal.AtomicReferences.lazyInit;
import static alpha.nomagichttp.internal.SubscriptionMonitoringOp.NO_DOWNSTREAM;
import static alpha.nomagichttp.internal.SubscriptionMonitoringOp.TerminationResult;
import static alpha.nomagichttp.internal.SubscriptionMonitoringOp.subscribeTo;
import static alpha.nomagichttp.message.DefaultContentHeaders.empty;
import static java.lang.System.Logger.Level.DEBUG;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.completedStage;
import static java.util.concurrent.CompletableFuture.failedStage;

/**
 * Default implementation of {@link Request.Body}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class RequestBody implements Request.Body
{
    private static final System.Logger
            LOG = System.getLogger(RequestBody.class.getPackageName());
    
    private static final CompletionStage<String>
            COMPLETED_EMPTY_STR = completedStage("");
    
    private static final CompletionStage<BetterHeaders>
            COMPLETED_TRAILERS = completedStage(empty());
    
    // Copy-pasted from AsynchronousFileChannel.NO_ATTRIBUTES
    @SuppressWarnings({"rawtypes"}) // generic array construction
    private static final FileAttribute<?>[] NO_ATTRIBUTES = new FileAttribute[0];
    
    /**
     * Create a request body.<p>
     * 
     * The optional callback will be called just before this class subscribes
     * the downstream subscriber (application) to the upstream publisher
     * (channel), but only if the body has contents. Subscription to an empty
     * body will delegate to an empty dummy and never invoke the callback.
     * 
     * @param headers of request
     * @param chIn reading channel
     * @param chApi extended channel API (used for exceptional closure)
     * @param maxTrailersSize passed to {@link ChunkedDecoderOp}
     * @param timeout duration
     * @param beforeNonEmptyBodySubscription before callback (nullable)
     * 
     * @return a request body
     * @throws BadRequestException on invalid message framing
     */
    static RequestBody of(
            DefaultContentHeaders headers,
            Flow.Publisher<DefaultPooledByteBufferHolder> chIn,
            DefaultClientChannel chApi,
            int maxTrailersSize,
            Duration timeout,
            Runnable beforeNonEmptyBodySubscription)
    {
        final Flow.Publisher<? extends PooledByteBufferHolder> content;
        final CompletionStage<BetterHeaders> trailers;
        
        var cl = headers.contentLength();
        var te = headers.transferEncoding();
        
        if (cl.isPresent()) {
            if (!te.isEmpty()) {
                throw new BadRequestException(
                    CONTENT_LENGTH + " and " + TRANSFER_ENCODING + " present.");
            }
            long len = cl.getAsLong();
            if (len == 0) {
                return emptyBody(headers);
            }
            assert len > 0;
            content = new LengthLimitedOp(len, chIn);
            trailers = null;
        } else if (te.isEmpty()) {
            // "If this is a request message [...] then"
            //  the message body length is zero (no message body is present)."
            // (only outbound responses may be close-delimited)
            // https://tools.ietf.org/html/rfc7230#section-3.3.3
            return emptyBody(headers);
        } else {
            if (te.size() != 1 && !te.getLast().equalsIgnoreCase("chunked")) {
                throw new UnsupportedOperationException(
                        "Only chunked decoding supported, at the moment.");
            }
            var chunked = new ChunkedDecoderOp(chIn, maxTrailersSize, chApi);
            content = chunked;
            trailers = chunked.trailers();
        }
        
        return contentBody(
                headers, trailers, content, timeout,
                chApi, beforeNonEmptyBodySubscription);
    }
    
    /**
     * Create an empty request body.
     * 
     * @param headers HTTP headers
     * @return an empty request body
     */
    public static RequestBody emptyBody(DefaultContentHeaders headers) {
        assert headers != null;
        return new RequestBody(headers, null, null, null);
    }
    
    private static RequestBody contentBody(
            DefaultContentHeaders headers,
            CompletionStage<BetterHeaders> trailers,
            Flow.Publisher<? extends PooledByteBufferHolder> content,
            Duration timeout,
            DefaultClientChannel chApi,
            Runnable beforeNonEmptyBodySubscription)
    {
        // Upstream is ChannelByteBufferPublisher, he can handle async cancel
        var top = new TimeoutOp.Flow<>(false, true, content, timeout, () -> {
                    if (LOG.isLoggable(DEBUG) && chApi.isOpenForReading()) {
                        LOG.log(DEBUG, """
                            Request body timed out, shutting down \
                            child channel's read stream.""");
                    }
                    // No new HTTP exchange
                    chApi.shutdownInputSafe();
                    return new RequestBodyTimeoutException();
                });
        top.start();
        return new RequestBody(
                headers, trailers, top,
                beforeNonEmptyBodySubscription);
    }
    
    private final ContentHeaders headers;
    private final CompletionStage<BetterHeaders> trailers;
    private final SubscriptionMonitoringOp monitor;
    private final OnCancelDiscardOp discard;
    private final Runnable beforeSub;
    private final AtomicReference<CompletionStage<String>> cachedTxt;
    
    private RequestBody(
            // Absolutely required
            ContentHeaders headers,
            // Only required if chunked body
            CompletionStage<BetterHeaders> trailers,
            // Not needed for empty bodies, obviously
            Flow.Publisher<? extends PooledByteBufferHolder> content,
            // Optional
            Runnable beforeSub)
    {
        this.headers   = headers;
        this.trailers  = trailers;
        if (content == null) {
            monitor = null;
            discard = null;
            cachedTxt = null;
        } else {
            monitor = subscribeTo(content);
            discard = new OnCancelDiscardOp(monitor);
            cachedTxt = new AtomicReference<>(null);
        }
        this.beforeSub = beforeSub;
    }
    
    @Override
    public CompletionStage<String> toText() {
        return isEmpty() ? COMPLETED_EMPTY_STR :
               lazyInit(cachedTxt, CompletableFuture::new,
                       v -> copyResult(mkText(), v));
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
        
        final AsynchronousFileChannel fc;
        
        try {
            // TODO: Potentially re-use server's async group
            //       (currently not possible to specify group?)
            fc = AsynchronousFileChannel.open(file, opt, null, attrs);
        } catch (Throwable t) {
            return failedStage(t);
        }
        
        var fs = new FileSubscriber(file, fc);
        subscribe(fs);
        return fs.result();
    }
    
    @Override
    public <R> CompletionStage<R> convert(BiFunction<byte[], Integer, R> f) {
        var hs = new HeapSubscriber<>(f);
        subscribe(hs);
        return hs.result();
    }
    
    @Override
    public void subscribe(Flow.Subscriber<? super PooledByteBufferHolder> subscriber) {
        if (isEmpty()) {
            Publishers.<PooledByteBufferHolder>empty().subscribe(subscriber);
        } else {
            if (beforeSub != null) {
                beforeSub.run();
            }
            discard.subscribe(subscriber);
        }
    }
    
    @Override
    public boolean isEmpty() {
        return monitor == null;
    }
    
    /**
     * Returns trailing headers.
     * 
     * @return trailing headers (never {@code null})
     * @see Request#trailers() 
     */
    CompletionStage<BetterHeaders> trailers() {
        return trailers == null? COMPLETED_TRAILERS : trailers;
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
    
    /**
     * Execute the action when both the downstream subscription and trailers
     * have completed.<p>
     * 
     * The subscription stage always terminates normally, the result of which is
     * passed forward to the given action.<p>
     * 
     * The successful result from trailers is discarded. Trailers may however
     * end exceptionally and if so, the throwable is passed forward to the
     * action.<p>
     * 
     * One does not exclude the other. The action is guaranteed to receive the
     * subscription's termination result, but may also receive an exception from
     * the trailers.
     * 
     * @param action see JavaDoc
     */
    void whenComplete(BiConsumer<? super TerminationResult, ? super Throwable> action) {
        assert action != null;
        // This entire method could be compressed to one simple "allOf()"
        // statement. But lots of requests are going to be bodiless or not use
        // trailers. As a library we have to care a little about performance.
        if (isEmpty()) {
            action.accept(NO_DOWNSTREAM, null);
            return;
        }
        var bodyStage = monitor.asCompletionStage();
        if (trailers == null) {
            bodyStage.whenComplete((res, nil) -> {
                assert nil == null;
                action.accept(res, null);
            });
        } else {
            var bodyFut = bodyStage.toCompletableFuture();
            allOf(bodyFut, trailers.toCompletableFuture())
                    .whenComplete((nil, thr) -> {
                        var res = bodyFut.getNow(null);
                        assert res != null;
                        action.accept(res, thr);
                    });
        }
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