package alpha.nomagichttp.util;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static alpha.nomagichttp.util.ByteBuffers.asArray;
import static java.nio.ByteBuffer.allocate;
import static java.nio.ByteBuffer.allocateDirect;
import static java.nio.ByteBuffer.wrap;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Small tests of {@link ByteBuffers}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ByteBuffersTest
{
    @Test
    void asArray_heap() {
        byte[] expected = {1, 2, 3};
        ByteBuffer buf = wrap(expected);
        assertThat(asArray(buf)).isEqualTo(expected);
        assertThat(buf.remaining()).isEqualTo(3);
    }
    
    @Test
    void asArray_direct() {
        byte[] expected = {1, 2, 3};
        ByteBuffer buf = allocateDirect(3);
        buf.put(0, expected);
        assertThat(asArray(buf)).isEqualTo(expected);
        assertThat(buf.remaining()).isEqualTo(3);
    }
    
    @Test
    void asArray_empty() {
        assertThat(asArray(allocate(0))).isEmpty();
    }
}