package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.PooledByteBufferHolder;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.function.IntConsumer;

import static java.nio.ByteBuffer.allocate;
import static java.nio.ByteBuffer.allocateDirect;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultPooledByteBufferHolderTest
{
    private static final ByteBuffer empty = allocate(0);
    
    @Test
    void callback_execution_order() {
        var executionOrder = new ArrayList<Integer>();
        // afterRelease last
        var testee = of(empty, ignored -> executionOrder.add(3));
        
        // onRelease by insertion order
        assertThat(testee.onRelease(ignored -> executionOrder.add(1))).isTrue();
        assertThat(testee.onRelease(ignored -> executionOrder.add(2))).isTrue();
        
        testee.release();
        assertThat(executionOrder).containsExactly(1, 2, 3);
    }
    
    @Test
    void copy_empty() {
        assertThat(of(empty).copy()).isEmpty();
    }
    
    @Test
    void copy_heap() {
        var buf = allocate(2);
        buf.put((byte) 1);
        buf.put((byte) 2);
        buf.flip();
        assertThat(of(buf).copy()).containsExactly(1, 2);
    }
    
    
    @Test
    void copy_direct() {
        var buf = allocateDirect(2);
        buf.put((byte) 1);
        buf.put((byte) 2);
        buf.flip();
        assertThat(of(buf).copy()).containsExactly(1, 2);
    }
    
    private static PooledByteBufferHolder of(ByteBuffer buf) {
        return of(buf, ignored -> {});
    }
    
    private static PooledByteBufferHolder of(ByteBuffer buf, IntConsumer afterRelease) {
        return new DefaultPooledByteBufferHolder(buf, afterRelease);
    }
}