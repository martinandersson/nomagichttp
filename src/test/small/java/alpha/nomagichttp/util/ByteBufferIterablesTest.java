package alpha.nomagichttp.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.MalformedInputException;
import java.util.List;

import static alpha.nomagichttp.testutil.Assertions.assertIterable;
import static alpha.nomagichttp.util.ByteBufferIterables.just;
import static alpha.nomagichttp.util.ByteBuffers.asArray;
import static alpha.nomagichttp.util.ByteBuffers.asciiBytes;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Small tests of {@link ByteBufferIterables}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ByteBufferIterablesTest
{
    @Test
    void just_iterable() throws IOException {
        var buf1 = asciiBytes("Hello");
        var buf2 = asciiBytes("World");
        var col = List.of(buf1, buf2);
        var testee = just(col);
        assertIterable(testee, buf1, buf2);
    }
    
    @Test
    void ofStringUnsafe_happyPath() {
        assertThat(runOfStringUnsafe("hello")).isEqualTo("hello");
    }
    
    @Test
    void ofStringUnsafe_javaDocExample_1() {
        assertThat(runOfStringUnsafe("\uD83F")).isEqualTo("?");
    }
    
    @Test
    void ofString() {
        assertThatThrownBy(() -> ByteBufferIterables.ofString("\uD83F"))
            .isExactlyInstanceOf(MalformedInputException.class)
            .hasMessage("Input length = 1")
            .hasNoSuppressedExceptions()
            .hasNoCause();
    }
    
    private static String runOfStringUnsafe(String input) {
        var iterable = ByteBufferIterables.ofStringUnsafe(input);
        assertThat(iterable.isEmpty()).isFalse();
        var it = iterable.iterator();
        assertThat(it.hasNext()).isTrue();
        final ByteBuffer enc;
        try {
            enc = it.next();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        assertThat(it.hasNext()).isFalse();
        assertThat(iterable.isEmpty()).isFalse();
        // If we were to decode using UTF_16, the replacement char is "�"
        return new String(asArray(enc), UTF_8);
    }
}