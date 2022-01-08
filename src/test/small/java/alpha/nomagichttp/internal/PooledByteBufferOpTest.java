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
        // Note: Decoder must complete before upstream do.
        // Has been tested in ChunkedDecoderOpTest.empty_prematurely()
        assertThat(toString(testee(nonDecoded(1), "hello")))
                .isEqualTo("hello");
    }
    
    @Test
    void empty() {
        assertThat(toString(testee(nonDecoded(1), "")))
                .isEmpty();
    }
    
    @Test
    void emptyThenOne() {
        assertThat(toString(testee(nonDecoded(2), "", "X")))
                .isEqualTo("X");
    }
    
    @Test
    void filtering() {
        // ["x--", "x--", ... "!"]
        final var N = 10;
        final var inputForDecoder = ("x--,".repeat(N) + '!').split(",");
        
        // Pass-through 'x' only, stop on '!'
        BiConsumer<ByteBuffer, PooledByteBufferOp.Sink> zipper = (buf, sink) -> {
            if (buf.get() == 'x') {
                sink.accept((byte) 'x');
            } else {
                sink.complete();
            }
        };
        
        var received = drainSignals(
            map(testee(zipper, inputForDecoder), ByteBuffers::toString));
        
        // Sorry, first test needs to be exact lol. Future ones will reduce.
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
    
    @Test
    void inflateBeyondBufferCapacity() {
        final int bytesNeeded = PooledByteBufferOp.BUF_SIZE * 3 + 1;
        
        BiConsumer<ByteBuffer, PooledByteBufferOp.Sink> inflator = (buf, sink) -> {
                generate(() -> 'y')
                    .limit(bytesNeeded)
                    .forEach(y -> sink.accept((byte) y));
                sink.complete();
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
    
    private static BiConsumer<ByteBuffer, PooledByteBufferOp.Sink>
            nonDecoded(int completeAfter)
    {
        return new BiConsumer<>() {
            int n = completeAfter;
            public void accept(ByteBuffer buf, PooledByteBufferOp.Sink s) {
                while (buf.hasRemaining()) {
                    s.accept(buf.get());
                }
                if (--n == 0) {
                    s.complete();
                }
            }
        };
    }
    
    private static String toString(Flow.Publisher<PooledByteBufferHolder> bytes) {
        var sub = new HeapSubscriber<>((buf, count) ->
                new String(buf, 0, count, US_ASCII));
        bytes.subscribe(sub);
        try {
            return sub.result().toCompletableFuture().get(1, SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException(e);
        }
    }
}