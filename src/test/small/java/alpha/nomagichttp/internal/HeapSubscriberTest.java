package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.testutil.ByteBuffers;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Function;

import static alpha.nomagichttp.util.Publishers.ofIterable;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Arrays.stream;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Small tests for {@link HeapSubscriber}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class HeapSubscriberTest
{
    private final HeapSubscriber<String> testee = new HeapSubscriber<>(TO_STRING);
    
    @Test
    void happyPath() {
        publish("hello").subscribe(testee);
        assertThat(actual()).isEqualTo("hello");
    }
    
    @Test
    void empty() {
        publish().subscribe(testee);
        assertThat(actual()).isEmpty();
    }
    
    @Test
    void join_heap() {
        publish("h", "e", "l", "l", "o").subscribe(testee);
        assertThat(actual()).isEqualTo("hello");
    }
    
    @Test
    void join_direct() {
        publishDirect("h", "e", "l", "l", "o").subscribe(testee);
        assertThat(actual()).isEqualTo("hello");
    }
    
    private static Flow.Publisher<PooledByteBufferHolder> publish(String... items) {
        return publish0(ByteBuffers::toByteBuffer, items);
    }
    
    private static Flow.Publisher<PooledByteBufferHolder> publishDirect(String... items) {
        return publish0(ByteBuffers::toByteBufferDirect, items);
    }
    
    private static Flow.Publisher<PooledByteBufferHolder> publish0(
            Function<String, ByteBuffer> allocator, String... items) {
        return ofIterable(stream(items)
                .map(allocator)
                .map(buf -> new DefaultPooledByteBufferHolder(buf, ignored -> {}))
                .toList());
    }
    
    private String actual() {
        try {
            return testee.toCompletionStage().toCompletableFuture().get(1, SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException(e);
        }
    }
    
    private static final BiFunction<byte[], Integer, String>
            TO_STRING = (buf, len) -> new String(buf, 0, len, US_ASCII);
}