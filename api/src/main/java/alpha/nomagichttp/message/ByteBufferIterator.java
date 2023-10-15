package alpha.nomagichttp.message;

import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.util.Throwing;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.NoSuchElementException;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/**
 * An iterator of bytebuffers.<p>
 * 
 * This interface extends {@link Closeable} for the same reason {@link Stream}
 * extends {@link AutoCloseable}; many iterators will not require releasing
 * resources but some may. Even if the implementation is backed by a system
 * resource, it may still choose to not release the resource, as is the case for
 * a {@link Request.Body} (the correct method to use would have been
 * {@link ClientChannel#shutdownInput()}).<p>
 * 
 * The {@code close} method is intended to be used only by {@link Response}
 * bodies backed by a file, and is called by the server. Application code should
 * never have a need to call {@code close}.<p>
 * 
 * The implementation is not thread-safe.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see ResourceByteBufferIterable
 */
public interface ByteBufferIterator extends Closeable
{
    /**
     * An empty iterator.
     */
    enum Empty implements ByteBufferIterator {
        /** The singleton instance. */
        INSTANCE;
        public boolean hasNext() {
            return false; }
        public ByteBuffer next() {
            throw new NoSuchElementException(); }
    }
    
    /**
     * Returns {@code true} if the iteration has more byte buffers.<p>
     * 
     * In other words, returns {@code true} if {@link #next} would
     * return a byte buffer rather than throwing {@link NoSuchElementException}.
     * 
     * @return see JavaDoc
     */
    boolean hasNext();
    
    /**
     * Returns the next byte buffer in the iteration.
     * 
     * @return the next byte buffer in the iteration (never {@code null})
     * 
     * @throws NoSuchElementException
     *             if the iteration has no more byte buffers
     * @throws IOException
     *             if an I/O error occurs
     */
    ByteBuffer next() throws IOException;
    
    /**
     * Performs the given action for each remaining byte buffer.<p>
     * 
     * Exceptions thrown by the action immediately propagate to the caller.
     * 
     * @implSpec
     * The default implementation is equivalent to:<p>
     * 
     * {@snippet :
     *   try (this) {
     *       while (hasNext())
     *           action.accept(next());
     *   }
     * }
     * 
     * @param action the action to be performed for each byte buffer
     * 
     * @throws NullPointerException
     *             if the specified action is {@code null}
     * @throws IOException
     *             if an I/O error occurs
     */
    default void forEachRemaining(
            Throwing.Consumer<? super ByteBuffer, ? extends IOException> action)
            throws IOException
    {
        try (this) {
            requireNonNull(action);
            while (hasNext()) {
                // On different lines for traceability
                var buf = next();
                action.accept(buf);
            }
        }
    }
    
    /**
     * {@inheritDoc}
     * 
     * @implSpec
     * The default implementation is empty.
     * 
     * @throws IOException if an I/O error occurs
     */
    @Override
    default void close() throws IOException {
        // Empty
    }
}