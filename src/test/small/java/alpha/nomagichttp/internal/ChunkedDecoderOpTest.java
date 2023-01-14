package alpha.nomagichttp.internal;

import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.message.DecoderException;
import alpha.nomagichttp.message.DefaultContentHeaders;
import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.testutil.ByteBuffers;
import alpha.nomagichttp.util.Headers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.BufferOverflowException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static alpha.nomagichttp.message.DefaultContentHeaders.empty;
import static alpha.nomagichttp.testutil.Assertions.assertCancelled;
import static alpha.nomagichttp.testutil.Assertions.assertPublisherEmits;
import static alpha.nomagichttp.testutil.Assertions.assertPublisherError;
import static alpha.nomagichttp.testutil.TestPublishers.reusable;
import static alpha.nomagichttp.util.Publishers.just;
import static alpha.nomagichttp.util.Publishers.map;
import static java.lang.Integer.MAX_VALUE;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.HexFormat.fromHexDigitsToLong;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Stream.concat;
import static java.util.stream.Stream.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Small tests for {@link ChunkedDecoderOp}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ChunkedDecoderOpTest
{
    @Test
    void happyPath_oneBuffer() {
        var testee = decode(
                // Size
                "1\r\n" +
                // Data
                "X\r\n" +
                // Empty last chunk
                "0\r\n" +
                // Empty trailers
                "\r\n");
        assertPublisherEmits(map(testee, ByteBuffers::toString), "X");
        assertEmptyTrailers(testee);
    }
    
    @Test
    void happyPath_distinctBuffers() {
        var testee = decode("5\r\n", "ABCDE\r\n", "0\r\n", "\r\n");
        assertThat(toString(testee)).isEqualTo("ABCDE");
        assertEmptyTrailers(testee);
    }
    
    @Test
    void happyPath_twoSeparatedDataChunks_twoPublishedItems() {
        var testee = decode("2\r\nAB\r\n", "2\r\nCD\r\n", "0\r\n\r\n");
        assertPublisherEmits(map(testee, ByteBuffers::toString), "AB", "CD");
    }
    
    @Test
    void happyPath_ifChunksFitOneReceivedBuffer_operatorProcessesBothIntoOne() {
        var testee = decode("2\r\nAB\r\n2\r\nCD\r\n", "0\r\n\r\n");
        assertPublisherEmits(map(testee, ByteBuffers::toString), "ABCD");
    }
    
    @Test
    void empty_deliberately() {
        var testee = decode("0\r\n\r\n ...subsequent-request-data in stream...");
        assertThat(toString(testee)).isEmpty();
        assertEmptyTrailers(testee);
    }
    
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void empty_prematurely(boolean emptyBuffer) {
        var testee = emptyBuffer ? decode("") : decode();
        assertPublisherError(testee)
            .isExactlyInstanceOf(AssertionError.class)
            .hasMessage("Unexpected: Channel closed gracefully before processor was done.")
            .hasNoCause()
            .hasNoSuppressedExceptions();
        assertCancelled(testee.trailers());
    }
    
    @Test
    void empty_thenSomething() {
        var testee = decode("", "1\r\nX\r\n0\r\n\r\n");
        assertThat(toString(testee)).isEqualTo("X");
        assertEmptyTrailers(testee);
    }
    
    @Test
    void parseSize_noLastChunk() {
        var testee = decode("1\r\nX\r\n\r\n");
        assertPublisherError(testee)
            .isExactlyInstanceOf(DecoderException.class)
            .hasNoSuppressedExceptions()
            .hasNoCause()
            .hasMessage("No chunk-size specified.");
        assertCancelled(testee.trailers());
    }
    
    @Test
    void parseSize_notHex() {
        // ';' triggers the size parsing
        var testee = decode("BOOM!;");
        assertPublisherError(testee)
            .isExactlyInstanceOf(DecoderException.class)
            .hasNoSuppressedExceptions()
            .cause()
                .isExactlyInstanceOf(NumberFormatException.class)
                .hasMessage("not a hexadecimal digit: \"O\" = 79");
        assertCancelled(testee.trailers());
    }
    
    private static final String FIFTEEN_F = "F".repeat(15);
    
    @Test
    void forYourConvenience() {
        // Java's HEX parser just don't like more than 16 chars lol
        assertThatThrownBy(() -> fromHexDigitsToLong(FIFTEEN_F + "17"))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("string length greater than 16: 17");
        
        // Fifteen F is equal to...
        long lotsOfChunkData = 1_152_921_504_606_846_975L;
        assertThat(fromHexDigitsToLong(FIFTEEN_F))
                .isEqualTo(lotsOfChunkData);
        
        // We can prepend a 16th char to get even more!
        Supplier<Stream<String>> one23456
                = () -> of("1", "2", "3", "4", "5", "6");
        one23456.get().forEach(n ->
                assertThat(fromHexDigitsToLong(n + FIFTEEN_F))
                    .isGreaterThan(lotsOfChunkData)
                    .isLessThan(Long.MAX_VALUE));
        
        // And .... this is the largest we can go
        assertThat(fromHexDigitsToLong("7" + FIFTEEN_F))
                .isEqualTo(Long.MAX_VALUE);
        
        // Prepended something bigger than 7, we start running into overflow issues
        Supplier<Stream<String>> eight9abcdef
                = () -> of("8", "9", "A", "B", "C", "D", "E", "F");
        eight9abcdef.get().forEach(n ->
                assertThat(fromHexDigitsToLong(n + FIFTEEN_F))
                    .isNegative()); // <-- please forgive the Java Gods
        
        // But we can't APPEND anything coz then shit overflows again
        concat(one23456.get(), concat(of("7"), eight9abcdef.get())).forEach(n ->
                assertThat(fromHexDigitsToLong(FIFTEEN_F + n))
                    .isNegative());
        
        // Hopefully THIS EXPLAINS the impl and the following two test cases lol
    }
    
    @Test
    void parseSize_overflow_moreThan16HexChars() {
        var testee = decode("17" + FIFTEEN_F + ";");
        assertPublisherError(testee)
            // It's not really a DECODER issue lol
            .isExactlyInstanceOf(UnsupportedOperationException.class)
            .hasNoSuppressedExceptions()
            .hasMessage("Long overflow.")
            .cause()
                .isExactlyInstanceOf(BufferOverflowException.class)
                .hasMessage(null);
        assertCancelled(testee.trailers());
    }
    
    @Test
    void parseSize_overflow_negativeResult() {
        var testee = decode(FIFTEEN_F + "1;");
        assertPublisherError(testee)
            .isExactlyInstanceOf(UnsupportedOperationException.class)
            .hasMessage("Long overflow for hex-value \"FFFFFFFFFFFFFFF1\".")
            .hasNoSuppressedExceptions()
            .hasNoCause();
        assertCancelled(testee.trailers());
    }
    
    @Test
    void chunkExtensions_discarded() {
        var testee = decode("1;bla=bla\r\nX\r\n0\r\n\r\n");
        assertThat(toString(testee)).isEqualTo("X");
        assertEmptyTrailers(testee);
    }
    
    @Test
    void chunkExtensions_quoted() {
        var testee = decode("1;bla=\"bla\".......");
        assertPublisherError(testee)
            .isExactlyInstanceOf(UnsupportedOperationException.class)
            .hasMessage("Quoted chunk-extension value.")
            .hasNoSuppressedExceptions()
            .hasNoCause();
        assertCancelled(testee.trailers());
    }
    
    @Test
    void chunkData_withCRLF() {
        var data = "\r\nX\r\n";
        var testee = decode(data.length() + "\r\n" + data + "\r\n0\r\n\r\n");
        assertThat(toString(testee)).isEqualTo(data);
        assertEmptyTrailers(testee);
    }
    
    @Test
    void chunkData_notEndingWithCRLF() {
        var testee = decode("1\r\nX0\r\n\r\n");
        assertPublisherError(testee)
                .isExactlyInstanceOf(DecoderException.class)
                .hasMessage("Expected CR and/or LF after chunk. " +
                            "Received (hex:0x30, decimal:48, char:\"0\").")
                .hasNoSuppressedExceptions()
                .hasNoCause();
        assertCancelled(testee.trailers());
    }
    
    // HeadersSubscriber already well tested, this is just for show
    @Test
    void trailers_two() {
        var testee = decode("""
                0
                Foo: bar
                Bar: foo\n
                """);
        assertThat(toString(testee)).isEmpty();
        assertThat(testee.trailers()).isCompletedWithValue(
                new DefaultContentHeaders(Headers.of(
                        "Foo", "bar", "Bar", "foo")));
    }
    
    private ChunkedDecoderOp decode(String... upstreamBuffers) {
        return new ChunkedDecoderOp(
                reusable(map(just(upstreamBuffers), ByteBuffers::toBuf)),
                MAX_VALUE, mock(ClientChannel.class));
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
    
    private static void assertEmptyTrailers(ChunkedDecoderOp testee) {
        assertThat(testee.trailers()).isCompletedWithValue(empty());
    }
}