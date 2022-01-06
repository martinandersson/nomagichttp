package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.testutil.ByteBuffers;
import alpha.nomagichttp.testutil.MemorizingSubscriber.Signal;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_COMPLETE;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_SUBSCRIBE;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.drainSignals;
import static alpha.nomagichttp.testutil.TestPublishers.map;
import static alpha.nomagichttp.util.Publishers.just;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.concurrent.TimeUnit.SECONDS;
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
    void empty_1() {
        assertThat(toString(testee(nonDecoded()))).isEmpty();
    }
    
    @Test
    void empty_2() {
        assertThat(toString(testee(nonDecoded(), ""))).isEmpty();
    }
    
    @Test
    void emptyThenOne() {
        assertThat(toString(testee(nonDecoded(), "", "x"))).isEqualTo("x");
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
            map(testee(zipper, inputForDecoder), ByteBuffers::toString));
        
        // Sorry, first test needs to be exact. Future ones will reduce.
        assertThat(received).hasSize(N + 2);
        assertThat(received.get(0).methodName())
                .isEqualTo(ON_SUBSCRIBE);
        // Delivered N items of 'x' (each onNext will implicitly flush)
        assertThat(received.subList(1, N + 1))
                .extracting(Signal::argument)
                .allMatch(isEqual("x"));
        assertThat(received.get(N + 1).methodName())
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
        var buffers = map(just(items), ByteBuffers::toByteBufferPooled);
        return new PooledByteBufferOp(buffers, decoder);
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
            return sub.toCompletionStage().toCompletableFuture().get(1, SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException(e);
        }
    }
}