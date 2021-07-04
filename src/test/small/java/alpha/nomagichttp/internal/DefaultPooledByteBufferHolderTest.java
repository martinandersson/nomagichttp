package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.PooledByteBufferHolder;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultPooledByteBufferHolderTest
{
    @Test
    void callback_execution_order() {
        List<Integer> order = new ArrayList<>();
        ByteBuffer empty = ByteBuffer.allocate(0);
        
        // afterRelease runs last, so given id 3
        PooledByteBufferHolder testee = new DefaultPooledByteBufferHolder(
                empty, x -> order.add(3));
        
        // onRelease executed insertion order
        assertThat(testee.onRelease(x -> order.add(1))).isTrue();
        assertThat(testee.onRelease(x -> order.add(2))).isTrue();
        
        testee.release();
        
        assertThat(order).containsExactly(1, 2, 3);
    }
}