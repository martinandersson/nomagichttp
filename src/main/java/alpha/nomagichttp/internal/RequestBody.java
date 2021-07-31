package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.util.Publishers;

import java.net.http.HttpHeaders;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.charset.Charset;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static alpha.nomagichttp.internal.AtomicReferences.lazyInit;
import static alpha.nomagichttp.util.Headers.contentType;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.failedStage;

/**
 * Default implementation of {@link Request.Body}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class RequestBody implements Request.Body
{
    // Copy-pasted from AsynchronousFileChannel.NO_ATTRIBUTES
    @SuppressWarnings({"rawtypes"}) // generic array construction
    private static final FileAttribute<?>[] NO_ATTRIBUTES = new FileAttribute[0];
    
    static Request.Body empty(HttpHeaders headers) {
        // Even an empty body must still validate the arguments,
        // for example toText() may complete with IllegalCharsetNameException.
        requireNonNull(headers);
        return new RequestBody(headers, null, null, null);
    }
    
    static Request.Body of(
            HttpHeaders headers,
            Flow.Publisher<PooledByteBufferHolder> source,
            Runnable beforeNonEmptyBodySubscription,
            Runnable afterNonEmptyBodySubscription)
    {
        requireNonNull(headers);
        requireNonNull(source);
        return new RequestBody(headers, source,
                beforeNonEmptyBodySubscription, afterNonEmptyBodySubscription);
    }
    
    private final HttpHeaders headers;
    private final Flow.Publisher<PooledByteBufferHolder> source;
    private final AtomicReference<CompletionStage<String>> cachedText;
    private final Runnable beforeSubscription;
    private final Runnable afterSubscription;
    
    private RequestBody(
            HttpHeaders headers,
            Flow.Publisher<PooledByteBufferHolder> source,
            Runnable beforeSubscription,
            Runnable afterSubscription)
    {
        this.headers = headers;
        this.source  = source;
        this.cachedText = new AtomicReference<>(null);
        this.beforeSubscription = beforeSubscription;
        this.afterSubscription = afterSubscription;
    }
    
    @Override
    public CompletionStage<String> toText() {
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
    public CompletionStage<Long> toFile(Path file, Set<? extends OpenOption> options, FileAttribute<?>... attrs) {
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
            if (beforeSubscription != null) {
                beforeSubscription.run();
            }
            source.subscribe(subscriber);
            if (afterSubscription != null) {
                afterSubscription.run();
            }
        }
    }
    
    @Override
    public boolean isEmpty() {
        return source == null;
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