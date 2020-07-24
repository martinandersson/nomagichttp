package alpha.nomagichttp.internal;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.BiFunction;

import static java.lang.Long.MAX_VALUE;
import static java.lang.System.Logger.Level.DEBUG;

/**
 * A subscriber that synchronously collects all available bytes in bytebuffers
 * into an expanding {@code byte[]} then {@code onComplete} calls a {@code
 * BiFunction}-finisher with the array together with a valid count of bytes that
 * can be safely read in order to produce the final result, exposed through a
 * {@code CompletionStage}.<p>
 * 
 * Useful for clients that wish to collect data from bytebuffers in order for it
 * to be converted into any other arbitrary Java type.<p>
 * 
 * The intermediate byte[] occupies heap-space and so this class should not be
 * used if the final destination does not also reside in memory as this could
 * possibly and unnecessarily move bytes from a <i>direct</i> bytebuffer source
 * into memory only to be sent back out again.<p>
 * 
 * Collecting more bytes than what can be hold in a byte[] will likely result in
 * an {@code OutOfMemoryError}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class HeapSubscriber<R> implements Flow.Subscriber<ByteBuffer>
{
    private static final System.Logger LOG = System.getLogger(HeapSubscriber.class.getPackageName());
    
    private final BiFunction<byte[], Integer, ? extends R> finisher;
    private final ExposedByteArrayOutputStream buf;
    private final CompletableFuture<R> result;
    
    HeapSubscriber(BiFunction<byte[], Integer, ? extends R> finisher) {
        this.finisher = finisher;
        this.buf = new ExposedByteArrayOutputStream();
        this.result = new CompletableFuture<>();
    }
    
    CompletionStage<R> asCompletionStage() {
        return result.minimalCompletionStage();
    }
    
    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        if (result.isDone()) {
            subscription.cancel();
            // This is actually breaking the specification, but we have no other choice.
            // https://github.com/reactive-streams/reactive-streams-jvm/issues/495
            throw new IllegalStateException("No support for subscriber re-use.");
        }
        
        subscription.request(MAX_VALUE);
    }
    
    @Override
    public void onNext(ByteBuffer item) {
        if (result.isDone()) {
            LOG.log(DEBUG, "Received bytes although I'm done.");
            return;
        }
        
        if (item.hasArray()) {
            buf.write(item.array(), item.arrayOffset(), item.remaining());
        } else {
            while (item.hasRemaining()) {
                buf.write(item.get());
            }
        }
    }
    
    @Override
    public void onError(Throwable throwable) {
        result.completeExceptionally(throwable);
    }
    
    @Override
    public void onComplete() {
        R product = finisher.apply(buf.buffer(), buf.count());
        result.complete(product);
    }
    
    /**
     * The purpose of this class is to not perform an "unnecessary" array-copy
     * when retrieving the collected bytes.
     */
    private final static class ExposedByteArrayOutputStream extends ByteArrayOutputStream {
        ExposedByteArrayOutputStream() {
            super(128);
        }
        
        int count() {
            return super.count;
        }
        
        byte[] buffer() {
            return super.buf;
        }
    }
}