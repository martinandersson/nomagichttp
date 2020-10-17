package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.PooledByteBufferHolder;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

import static java.util.Objects.requireNonNull;

/**
 * The default implementation of {@code PooledByteBufferHolder} with a special
 * feature for package-friends: The buffer may be limited or "sliced" so that
 * client code doesn't accidentally read past the position of where a logical
 * message ends.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class DefaultPooledByteBufferHolder implements PooledByteBufferHolder
{
    private ByteBuffer buf;
    private volatile ByteBuffer view;
    private final int thenRemaining;
    private final AtomicReference<IntConsumer> onRelease;
    
    DefaultPooledByteBufferHolder(ByteBuffer buf, IntConsumer onRelease) {
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
        IntConsumer f = onRelease.getAndSet(null);
        if (f == null) {
            return;
        }
        
        try {
            if (buf != view) {
                // Update source position
                buf.position(buf.position() + view.position());
            }
            int read = thenRemaining - buf.remaining();
            f.accept(read);
        } finally {
            buf = null;
            view = null;
        }
    }
    
    @Override
    public boolean onRelease(IntConsumer onRelease) {
        requireNonNull(onRelease);
        
        IntConsumer c1 = this.onRelease.get();
        if (c1 == null) {
            return false;
        }
        
        return this.onRelease.compareAndSet(c1, c1.andThen(onRelease));
    }
    
    void limit(int newLimit) {
        // Possible NPE if method is used after release; which we assume will never happen.
        view = buf.slice().limit(newLimit);
    }
}