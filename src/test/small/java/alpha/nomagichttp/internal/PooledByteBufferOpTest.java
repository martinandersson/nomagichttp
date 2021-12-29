package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.testutil.MemorizingSubscriber.Signal;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.function.BiConsumer;

import static alpha.nomagichttp.message.PooledByteBufferHolder.discard;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal.MethodName.ON_COMPLETE;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal.MethodName.ON_SUBSCRIBE;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.drainSignals;
import static alpha.nomagichttp.testutil.TestPublishers.map;
import static alpha.nomagichttp.util.Publishers.just;
import static java.nio.ByteBuffer.wrap;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Arrays.stream;
import static java.util.function.Predicate.isEqual;
import static java.util.stream.IntStream.generate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Small tests for {@link PooledByteBufferOp}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class PooledByteBufferOpTest
{
    @Test
    void happyPath() {
        assertThat(toString(testee(nonDecoded(), "hello"))).isEqualTo("hello");
    }
    
    @Test
    void empty() {
        assertThat(toString(testee(nonDecoded()))).isEmpty();
    }
    
    // No explicit complete() from decoder
    @Test
    void filtering() {
        // ["x--", "x--", ...]
        final var N = 10;
        final var inputForDecoder = "x--,".repeat(N).split(",");
        
        // Pass-through 'x' only
        BiConsumer<ByteBuffer, PooledByteBufferOp.Sink>
                zipper = (ignored, s) -> s.accept((byte) 'x');
        
        var received = drainSignals(
            map(testee(zipper, inputForDecoder), PooledByteBufferOpTest::toString));
        
        // Sorry, first test needs to be exact. Future ones will reduce.
        assertThat(received).hasSize(N + 2);
        assertThat(received.get(0).getMethodName())
                .isEqualTo(ON_SUBSCRIBE);
        // Delivered N items of 'x' (each onNext will implicitly flush)
        assertThat(received.subList(1, N + 1))
                .<String>extracting(Signal::getArgument)
                .allMatch(isEqual("x"));
        assertThat(received.get(N + 1).getMethodName())
                .isEqualTo(ON_COMPLETE);
    }
    
    // With explicit complete() from decoder
    @Test
    void inflateBeyondBufferCapacity() {
        final int bytesNeeded = PooledByteBufferOp.BUF_SIZE * 3 + 1;
        
        BiConsumer<ByteBuffer, PooledByteBufferOp.Sink> inflator
            = (ignored, s) -> {
                generate(() -> 'y')
                    .limit(bytesNeeded)
                    .forEach(y -> s.accept((byte) y));
                s.complete();
            };
        
        var all = toString(testee(inflator, "x"));
        assertThat(all).hasSize(bytesNeeded);
        assertThat(all.chars()).containsOnly((int) 'y');
    }
    
    
    
    private static PooledByteBufferOp testee(
            BiConsumer<ByteBuffer, PooledByteBufferOp.Sink> decoder, String... items)
    {
        var boxed = stream(items)
                .map(PooledByteBufferOpTest::toByteBuffer)
                .map(buf -> new DefaultPooledByteBufferHolder(buf, ignored -> {}))
                .toArray(DefaultPooledByteBufferHolder[]::new);
        return new PooledByteBufferOp(just(boxed), decoder);
    }
    
    private static BiConsumer<ByteBuffer, PooledByteBufferOp.Sink> nonDecoded() {
        return (buf, sink) -> {
            while (buf.hasRemaining()) {
                sink.accept(buf.get());
            }
        };
    }
    
    private static String toString(Flow.Publisher<PooledByteBufferHolder> bytes) {
        var sub = new HeapSubscriber<>((buf, count) ->
                new String(buf, 0, count, US_ASCII));
        bytes.subscribe(sub);
        try {
            return sub.asCompletionStage().toCompletableFuture().get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }
    
    private static String toString(PooledByteBufferHolder from) {
        var str = toString(from.get());
        discard(from);
        return str;
    }
    
    private static String toString(ByteBuffer buf) {
        var str = new String(buf.array(), 0, buf.remaining(), US_ASCII);
        buf.position(buf.position() + buf.remaining());
        return str;
    }
    
    private static ByteBuffer toByteBuffer(String str) {
        return wrap(str.getBytes(US_ASCII));
    }
}