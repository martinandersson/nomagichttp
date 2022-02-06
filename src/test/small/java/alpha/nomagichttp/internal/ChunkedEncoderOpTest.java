package alpha.nomagichttp.internal;

import org.junit.jupiter.api.Test;

import static alpha.nomagichttp.testutil.Assertions.assertPublisherEmits;
import static alpha.nomagichttp.testutil.ByteBuffers.toBuf;
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
            toBuf("hello"),
            toBuf("world!")));
        assertPublisherEmits(testee,
            toBuf("00000005\r\n"),
            toBuf("hello"),
            toBuf("\r\n"),
            toBuf("00000006\r\n"),
            toBuf("world!"),
            toBuf("\r\n"),
            toBuf("0\r\n"));
    }
    
    @Test
    void empty_1() {
        var testee = new ChunkedEncoderOp(
            empty());
        assertPublisherEmits(testee,
            toBuf("0\r\n"));
    }
    
    @Test
    void empty_2() {
        var testee = new ChunkedEncoderOp(just(
            toBuf("")));
        assertPublisherEmits(testee,
            toBuf("0\r\n"));
    }
}