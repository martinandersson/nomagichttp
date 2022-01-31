package alpha.nomagichttp.internal;

import org.junit.jupiter.api.Test;

import static alpha.nomagichttp.testutil.Assertions.assertPublisherEmits;
import static alpha.nomagichttp.testutil.ByteBuffers.toByteBuffer;
import static alpha.nomagichttp.util.Publishers.empty;
import static alpha.nomagichttp.util.Publishers.just;

/**
 * Small tests for {@link ChunkedEncoderOp}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ChunkedEncoderOpTest
{
    @Test
    void happyPath() {
        var testee = new ChunkedEncoderOp(just(
            toByteBuffer("hello"),
            toByteBuffer("world!")));
        assertPublisherEmits(testee,
            toByteBuffer("00000005\r\n"),
            toByteBuffer("hello"),
            toByteBuffer("\r\n"),
            toByteBuffer("00000006\r\n"),
            toByteBuffer("world!"),
            toByteBuffer("\r\n"),
            toByteBuffer("0\r\n\r\n"));
    }
    
    @Test
    void empty_1() {
        var testee = new ChunkedEncoderOp(
            empty());
        assertPublisherEmits(testee,
            toByteBuffer("0\r\n\r\n"));
    }
    
    @Test
    void empty_2() {
        var testee = new ChunkedEncoderOp(just(
            toByteBuffer("")));
        assertPublisherEmits(testee,
            toByteBuffer("0\r\n\r\n"));
    }
}