package alpha.nomagichttp.core;

import alpha.nomagichttp.message.DecoderException;
import alpha.nomagichttp.util.FileLockTimeoutException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.util.NoSuchElementException;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static alpha.nomagichttp.testutil.Assertions.assertIterable;
import static alpha.nomagichttp.testutil.ByteBufferIterables.getString;
import static alpha.nomagichttp.testutil.ByteBufferIterables.just;
import static alpha.nomagichttp.util.ByteBuffers.asciiBytes;
import static java.util.HexFormat.fromHexDigitsToLong;
import static java.util.stream.Stream.concat;
import static java.util.stream.Stream.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Small tests for {@link ChunkedDecoder}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ChunkedDecoderTest
{
    @Test
    void happyPath_oneBuffer()
        throws InterruptedException, FileLockTimeoutException, IOException {
        var testee = decode(
                // Size
                "1\r\n" +
                // Data
                "X\r\n" +
                // Empty last chunk
                "0\r\n" +
                // Empty trailers
                "\r\n");
        assertIterable(testee, asciiBytes("X"));
        // Single-use only
        assertThat(testee.length()).isEqualTo(0);
        assertThat(testee.isEmpty()).isTrue();
    }
    
    @Test
    void happyPath_distinctBuffers_1()
        throws InterruptedException, FileLockTimeoutException, IOException {
        var testee = decode("5\r\n", "ABCDE\r\n", "0\r\n", "\r\n");
        assertIterable(testee, asciiBytes("ABCDE"));
    }
    
    @Test
    void happyPath_distinctBuffers_2()
        throws InterruptedException, FileLockTimeoutException, IOException {
        var testee = decode("2\r\nAB\r\n", "2\r\nCD\r\n", "0\r\n\r\n");
        assertIterable(testee, asciiBytes("ABCD"));
    }
    
    @Test
    void empty_1()
        throws InterruptedException, FileLockTimeoutException, IOException {
        var testee = decode("0\r\n\r\n ...trailing data in stream...");
        assertIterable(testee, asciiBytes(""));
        // Or, alternatively:
        assertThat(getString(testee)).isEmpty();
    }
    
    @Test
    void empty_2() {
        var testee = decode();
        assertThatThrownBy(() -> getString(testee))
            .isExactlyInstanceOf(DecoderException.class)
            .hasMessage("Upstream is empty but decoding is not done.")
            .hasNoSuppressedExceptions()
            .cause()
                // From alpha.nomagichttp.message.ByteBufferIterator$Empty.next
                .isExactlyInstanceOf(NoSuchElementException.class)
                .hasMessage(null)
                .hasNoSuppressedExceptions()
                .hasNoCause();
    }
    
    @Test
    void parseSize_noLastChunk() {
        var testee = decode("1\r\nX\r\n\r\n");
        assertThatThrownBy(() -> getString(testee))
            .isExactlyInstanceOf(DecoderException.class)
            .hasMessage("No chunk-size specified.")
            .hasNoSuppressedExceptions()
            .hasNoCause();
    }
    
    @Test
    void parseSize_notHex() {
        // ';' triggers the size parsing
        var testee = decode("BOOM!;");
        assertThatThrownBy(() -> getString(testee))
            .isExactlyInstanceOf(DecoderException.class)
            .hasMessage("""
                 java.lang.NumberFormatException: \
                 not a hexadecimal digit: "O" = 79""")
            .hasNoSuppressedExceptions()
            .cause()
                .isExactlyInstanceOf(NumberFormatException.class)
                .hasMessage("not a hexadecimal digit: \"O\" = 79");
    }
    
    private static final String FIFTEEN_F = "F".repeat(15);
    
    @Test
    void forYourConvenience() {
        // Java's HEX parser just doesn't like more than 16 chars lol
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
        
        // Prepended something bigger than 7 and value overflows
        Supplier<Stream<String>> eight9abcdef
                = () -> of("8", "9", "A", "B", "C", "D", "E", "F");
        eight9abcdef.get().forEach(n ->
                assertThat(fromHexDigitsToLong(n + FIFTEEN_F))
                    .isNegative()); // <-- please forgive the Java Gods
        
        // But we can't APPEND anything coz then overflow
        concat(one23456.get(), concat(of("7"), eight9abcdef.get())).forEach(n ->
                assertThat(fromHexDigitsToLong(FIFTEEN_F + n))
                    .isNegative());
        
        // Hopefully THIS EXPLAINS the impl and the following two test cases lol
    }
    
    @Test
    void parseSize_overflow_moreThan16HexChars() {
        var testee = decode("17" + FIFTEEN_F + ";");
        assertThatThrownBy(() -> getString(testee))
            // It's not really a DECODER issue lol
            .isExactlyInstanceOf(UnsupportedOperationException.class)
            .hasMessage("Long overflow.")
            .hasNoSuppressedExceptions()
            .cause()
                .isExactlyInstanceOf(BufferOverflowException.class)
                .hasMessage(null);
    }
    
    @Test
    void parseSize_overflow_negativeResult() {
        var testee = decode(FIFTEEN_F + "1;");
        assertThatThrownBy(() -> getString(testee))
            .isExactlyInstanceOf(UnsupportedOperationException.class)
            .hasMessage("Long overflow for hex-value \"FFFFFFFFFFFFFFF1\".")
            .hasNoSuppressedExceptions()
            .hasNoCause();
    }
    
    @Test
    void chunkExtensions_discarded() {
        var testee = decode("1;bla=bla\r\nX\r\n0\r\n\r\n");
        assertThat(getString(testee)).isEqualTo("X");
    }
    
    @Test
    void chunkExtensions_quoted() {
        var testee = decode("1;bla=\"bla\".......");
        assertThatThrownBy(() -> getString(testee))
            .isExactlyInstanceOf(UnsupportedOperationException.class)
            .hasMessage("Quoted chunk-extension value.")
            .hasNoSuppressedExceptions()
            .hasNoCause();
    }
    
    @Test
    void chunkData_withCRLF() {
        var data = "\r\nX\r\n";
        var testee = decode(data.length() + "\r\n" + data + "\r\n0\r\n\r\n");
        assertThat(getString(testee)).isEqualTo(data);
    }
    
    @Test
    void chunkData_notEndingWithCRLF() {
        var testee = decode("1\r\nX0\r\n\r\n");
        assertThatThrownBy(() -> getString(testee))
                .isExactlyInstanceOf(DecoderException.class)
                .hasMessage("Expected CR and/or LF after chunk. " +
                            "Received (hex:0x30, decimal:48, char:\"0\").")
                .hasNoSuppressedExceptions()
                .hasNoCause();
    }
    
    private ChunkedDecoder decode(String... items) {
        return new ChunkedDecoder(just(items));
    }
}