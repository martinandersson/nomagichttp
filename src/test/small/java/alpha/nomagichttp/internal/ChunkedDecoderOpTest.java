package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.DecoderException;
import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.testutil.ByteBuffers;
import org.assertj.core.api.AbstractThrowableAssert;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeoutException;

import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal.MethodName.ON_COMPLETE;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal.MethodName.ON_ERROR;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal.MethodName.ON_SUBSCRIBE;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.drainSignals;
import static alpha.nomagichttp.testutil.TestClient.CRLF;
import static alpha.nomagichttp.testutil.TestPublishers.map;
import static alpha.nomagichttp.util.Publishers.just;
import static java.lang.String.join;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Arrays.stream;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Small tests for {@link ChunkedDecoderOp}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ChunkedDecoderOpTest
{
    @Test
    void happyPath_one() {
        var data = decode(
                // Size
                "1" + CRLF +
                // Data
                "x" + CRLF +
                // Empty last chunk
                "0" + CRLF);
        
        var received = drainSignals(map(data, ByteBuffers::toString));
        
        assertThat(received).hasSize(3);
        assertThat(received.get(0).getMethodName()).isEqualTo(ON_SUBSCRIBE);
        assertThat(received.get(1).<String>getArgument()).isEqualTo("x");
        assertThat(received.get(2).getMethodName()).isEqualTo(ON_COMPLETE);
    }
    
    @Test
    void happyPath_three() {
        var data = decode(join(CRLF, "3", "abc", "0") + CRLF);
        assertThat(toString(data)).isEqualTo("abc");
    }
    
    @Test
    void empty_1() {
        var data = decode("0" + CRLF);
        assertThat(toString(data)).isEmpty();
    }
    
    @Test
    void empty_2() {
        var data = decode();
        assertThat(toString(data)).isEmpty();
    }
    
    @Test
    void empty_3() {
        var data = decode("");
        assertThat(toString(data)).isEmpty();
    }
    
    @Test
    void publishEmptyThenOne() {
        var data = decode("", join(CRLF, "1", "y", "0") + CRLF);
        assertThat(toString(data)).isEqualTo("y");
    }
    
    @Test
    void parseSize_DecodeException() {
        assertOnError(decode("X;"))
            .isExactlyInstanceOf(DecoderException.class)
            .hasNoSuppressedExceptions()
            .getCause()
            .isExactlyInstanceOf(NumberFormatException.class)
            .hasMessage("not a hexadecimal digit: \"X\" = 88");
    }
    
    @Test
    void chunkExtensions_discarded() {
        var data = decode("1;bla=bla" + CRLF + "z" + CRLF);
        assertThat(toString(data)).isEqualTo("z");
    }
    
    @Test
    void chunkExtensions_quoted() {
        assertOnError(decode("1;bla=\"bla\""))
            .isExactlyInstanceOf(UnsupportedOperationException.class)
            .hasMessage("Quoted chunk-extension value.")
            .hasNoSuppressedExceptions()
            .hasNoCause();
    }
    
    @Test
    void chunkDataWithCRLF() {
        var data = decode(join(CRLF, "3", CRLF + "a", "0") + CRLF);
        assertThat(toString(data)).isEqualTo(CRLF + "a");
    }
    
    @Test
    void chunkDataNotEndingWithCRLF() {
        assertOnError(decode(join(CRLF, "1", "bX", "0")))
                .isExactlyInstanceOf(DecoderException.class)
                .hasMessage("Expected CR and/or LF after chunk. " +
                            "Received (hex:0x58, decimal:88, char:\"X\").")
                .hasNoSuppressedExceptions()
                .hasNoCause();
    }
    
    private Flow.Publisher<PooledByteBufferHolder> decode(String... buffers) {
        return new ChunkedDecoderOp(just(stream(buffers)
                .map(ByteBuffers::toByteBuffer)
                .map(b -> new DefaultPooledByteBufferHolder(b, ignored -> {}))
                .toArray(DefaultPooledByteBufferHolder[]::new)));
    }
    
    private static String toString(Flow.Publisher<PooledByteBufferHolder> bytes) {
        var sub = new HeapSubscriber<>((buf, count) ->
                new String(buf, 0, count, US_ASCII));
        bytes.subscribe(sub);
        try {
            return sub.asCompletionStage().toCompletableFuture().get(1, SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException(e);
        }
    }
    
    private static AbstractThrowableAssert<?, ? extends Throwable>
            assertOnError(Flow.Publisher<PooledByteBufferHolder> data)
    {
        var received = drainSignals(data);
        assertThat(received).hasSize(2);
        assertThat(received.get(0).getMethodName()).isEqualTo(ON_SUBSCRIBE);
        assertThat(received.get(1).getMethodName()).isEqualTo(ON_ERROR);
        return assertThat(received.get(1).<Throwable>getArgument());
    }
}