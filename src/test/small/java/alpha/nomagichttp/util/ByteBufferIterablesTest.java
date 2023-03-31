package alpha.nomagichttp.util;

import alpha.nomagichttp.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.MalformedInputException;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static alpha.nomagichttp.Config.DEFAULT;
import static alpha.nomagichttp.testutil.Assertions.assertIterable;
import static alpha.nomagichttp.testutil.TestFiles.writeTempFile;
import static alpha.nomagichttp.util.ByteBufferIterables.just;
import static alpha.nomagichttp.util.ByteBuffers.asArray;
import static alpha.nomagichttp.util.ByteBuffers.asciiBytes;
import static alpha.nomagichttp.util.DummyScopedValue.where;
import static alpha.nomagichttp.util.ScopedValues.__HTTP_SERVER;
import static java.nio.ByteBuffer.allocate;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Small tests of {@link ByteBufferIterables}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ByteBufferIterablesTest
{
    final static ByteBuffer HELLO = asciiBytes("Hello"),
                            WORLD = asciiBytes("World");
    
    @Test
    void just_iterable()
            throws InterruptedException, TimeoutException, IOException {
        var col = List.of(HELLO, WORLD);
        var testee = just(col);
        assertIterable(testee, HELLO, WORLD);
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
    
    @Test
    void ofFile() throws Exception {
        var content = asciiBytes("Hello, World!");
        var file = writeTempFile(content);
        var testee = ByteBufferIterables.ofFile(file);
        var server = mock(HttpServer.class);
        when(server.getConfig()).thenReturn(DEFAULT);
        where(__HTTP_SERVER, server, () -> {
            assertIterable(testee, content);
            // Can go again
            assertIterable(testee, content);
            return null;
        });
    }
    
    // TODO: Test ofFile file not found
    
    @Test
    void ofSupplier()
            throws InterruptedException, TimeoutException, IOException {
        var empty = allocate(0);
        var col = List.of(HELLO, WORLD, empty);
        var testee = ByteBufferIterables.ofSupplier(col.iterator()::next);
        assertIterable(testee, HELLO, WORLD, empty);
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