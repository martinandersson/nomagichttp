package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.util.ExposedByteArrayOutputStream;

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
 * Useful for clients that wish to collect data from bytebuffers in order for
 * the data to be converted into any other arbitrary Java type.<p>
 * 
 * Collecting more bytes than what can be hold in a byte[] will likely result in
 * an {@code OutOfMemoryError}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class HeapSubscriber<R> implements SubscriberAsStage<PooledByteBufferHolder, R>
{
    private static final System.Logger LOG = System.getLogger(HeapSubscriber.class.getPackageName());
    
    private final BiFunction<byte[], Integer, ? extends R> finisher;
    private final ExposedByteArrayOutputStream sink;
    private final CompletableFuture<R> result;
    private Flow.Subscription subscription;
    
    HeapSubscriber(BiFunction<byte[], Integer, ? extends R> finisher) {
        this.finisher = finisher;
        this.sink = new ExposedByteArrayOutputStream(128);
        this.result = new CompletableFuture<>();
        this.subscription = null;
    }
    
    @Override
    public CompletionStage<R> asCompletionStage() {
        return result;
    }
    
    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = SubscriberAsStage.validate(this.subscription, subscription);
        subscription.request(MAX_VALUE);
    }
    
    @Override
    public void onNext(PooledByteBufferHolder item) {
        onNext(item.get());
        item.release();
    }
    
    private void onNext(ByteBuffer buf) {
        if (result.isDone()) {
            LOG.log(DEBUG, "Received bytes although I'm done.");
            return;
        }
        if (buf.hasArray()) {
            final var len = buf.remaining();
            sink.write(buf.array(), buf.arrayOffset(), len);
            buf.position(buf.position() + len);
        } else {
            while (buf.hasRemaining()) {
                sink.write(buf.get());
            }
        }
    }
    
    @Override
    public void onError(Throwable t) {
        result.completeExceptionally(t);
    }
    
    private static final byte[] EMPTY = new byte[0];
    
    @Override
    public void onComplete() {
        final int len = sink.count();
        final R product = len == 0 ?
                finisher.apply(EMPTY, 0) :
                finisher.apply(sink.buffer(), len);
        
        result.complete(product);
    }
}