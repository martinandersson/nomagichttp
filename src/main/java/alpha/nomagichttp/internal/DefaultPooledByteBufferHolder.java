package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.PooledByteBufferHolder;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.util.function.UnaryOperator;

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
    private static final IntConsumer NOOP = x -> {},
                                     RELEASED = noopNew();
    
    private static IntConsumer noopNew() {
        // MUST BE NEW INSTANCE, no lambda
        return new IntConsumer() {
            @Override public void accept(int value) {
                // Empty
            }
        };
    }
    
    private ByteBuffer buf;
    private volatile ByteBuffer view;
    private final int thenRemaining;
    private final AtomicReference<IntConsumer> onRelease;
    private final IntConsumer afterRelease;
    
    /**
     * Constructs a {@code DefaultPooledByteBufferHolder}.<p>
     * 
     * The {@code afterRelease} callback is executed after other callbacks
     * possibly registered using the method {@link #onRelease(IntConsumer)}.
     * {@code afterRelease} is guaranteed to be called even if an on-release
     * callback throws an exception.
     * 
     * @param buf bytebuffer source
     * @param afterRelease a sort of try-release-finally callback (may be {@code null})
     */
    DefaultPooledByteBufferHolder(ByteBuffer buf, IntConsumer afterRelease) {
        this.buf = view = buf;
        this.thenRemaining = buf.remaining();
        this.onRelease = new AtomicReference<>(NOOP);
        this.afterRelease = afterRelease != null ? afterRelease : NOOP;
    }
    
    @Override
    public ByteBuffer get() {
        return view;
    }
    
    @Override
    public void release() {
        final IntConsumer f = onRelease.getAndSet(RELEASED);
        if (f == RELEASED) {
            return;
        }
        
        if (buf != view) {
            // Update source position
            buf.position(buf.position() + view.position());
        }
        
        final int read = thenRemaining - buf.remaining();
        try {
            f.accept(read);
        } finally {
            buf = null;
            view = null;
            afterRelease.accept(read);
        }
    }
    
    @Override
    public boolean onRelease(IntConsumer onRelease) {
        requireNonNull(onRelease);
        UnaryOperator<IntConsumer> keepReleasedOrAdd = v ->
                v == RELEASED ? RELEASED : v.andThen(onRelease);
        return this.onRelease.getAndUpdate(keepReleasedOrAdd) != RELEASED;
    }
    
    void limit(int newLimit) {
        // Possible NPE if method is used after release; which we assume will never happen.
        view = buf.slice().limit(newLimit);
    }
}