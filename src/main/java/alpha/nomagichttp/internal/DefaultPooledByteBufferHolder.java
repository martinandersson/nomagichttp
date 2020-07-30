package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.PooledByteBufferHolder;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Is a {@code PooledByteBufferHolder} with two special features for package
 * friends:<p>
 * 
 * The buffer may be limited or "sliced" so that client code doesn't
 * accidentally read past the position of where a logical message ends. This
 * obviously also makes life a bit easier for client code consuming the
 * buffer.<p>
 * 
 * The holder can be attached with joined logic that executes orderly on
 * release. This notifies any number of stakeholders and grants them the ability
 * to perform consumption life-cycle maintenance.<p>
 * 
 * The on-release function will receive the bytebuffer as well as a count of how
 * many bytes were read prior to releasing.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class DefaultPooledByteBufferHolder implements PooledByteBufferHolder
{
    private ByteBuffer buf, view;
    private final int thenRemaining;
    private final AtomicReference<BiConsumer<ByteBuffer, Integer>> onRelease;
    
    DefaultPooledByteBufferHolder(ByteBuffer buf, BiConsumer<ByteBuffer, Integer> onRelease) {
        this.buf = view = buf;
        this.thenRemaining = buf.remaining();
        this.onRelease = new AtomicReference<>(onRelease);
    }
    
    @Override
    public ByteBuffer get() {
        return view;
    }
    
    @Override
    public void release() {
        BiConsumer<ByteBuffer, Integer> f = onRelease.getAndSet(null);
        if (f == null) {
            return;
        }
        
        try {
            if (buf != view) {
                // Update source position
                buf.position(buf.position() + view.position());
            }
            int read = thenRemaining - buf.remaining();
            f.accept(buf, read);
        } finally {
            buf = null;
            view = null;
        }
    }
    
    void onRelease(BiConsumer<ByteBuffer, Integer> onRelease) {
        BiConsumer<ByteBuffer, Integer> c1 = this.onRelease.get();
        if (c1 == null) {
            throw new IllegalStateException("Already released.");
        }
        
        BiConsumer<ByteBuffer, Integer> c2 = c1.andThen(onRelease);
        
        if (!this.onRelease.compareAndSet(c1, c2)) {
            throw new IllegalStateException("Already released or invoked concurrently.");
        }
    }
    
    void limit(int newLimit) {
        view = buf.slice().limit(newLimit);
    }
}