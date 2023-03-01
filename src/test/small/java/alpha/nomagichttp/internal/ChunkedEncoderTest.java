package alpha.nomagichttp.internal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import static alpha.nomagichttp.testutil.Assertions.assertIterable;
import static alpha.nomagichttp.util.Blah.asciiBytes;
import static alpha.nomagichttp.util.ByteBufferIterables.just;

/**
 * Small tests for {@link ChunkedEncoder}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ChunkedEncoderTest
{
    @Test
    void happyPath() throws IOException {
        var testee = encode(
            asciiBytes("hello"),
            asciiBytes("world!"));
        assertIterable(testee,
            asciiBytes("00000005\r\n"),
            asciiBytes("hello"),
            asciiBytes("\r\n"),
            asciiBytes("00000006\r\n"),
            asciiBytes("world!"),
            asciiBytes("\r\n"),
            asciiBytes("0\r\n"));
    }
    
    @Test
    void empty() throws IOException {
        assertIterable(encode(), asciiBytes("0\r\n"));
    }
    
    private static ChunkedEncoder encode(ByteBuffer... contents) {
        return new ChunkedEncoder(just(List.of(contents)).iterator());
    }
}