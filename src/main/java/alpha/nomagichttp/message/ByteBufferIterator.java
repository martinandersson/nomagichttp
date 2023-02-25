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
        INSTANCE;
        public boolean hasNext() {
            return false; }
        public ByteBuffer next() {
            throw new NoSuchElementException(); }
    }
    
    /**
     * Returns {@code true} if the iteration has more bytebuffers.<p>
     * 
     * In other words, returns {@code true} if {@link #next} would
     * return a bytebuffer rather than throwing {@link NoSuchElementException}.
     * 
     * @return see JavaDoc
     */
    // TODO: Does not throw IOException as of today,
    //       coz I think we can, for file iterator, impl hasNext as desire and not use Files.size.
    //       Remove comment after first file iterator impl if still true.
    boolean hasNext();
    
    /**
     * Returns the next bytebuffer in the iteration.
     * 
     * @return the next bytebuffer in the iteration (never {@code null})
     * 
     * @throws NoSuchElementException
     *             if the iteration has no more bytebuffers
     * @throws IOException
     *             if an I/O error occurs
     */
    ByteBuffer next() throws IOException;
    
    /**
     * Performs the given action for each remaining bytebuffer.<p>
     * 
     * Exceptions thrown by the action immediately propagates to the caller.<p>
     * 
     * @implSpec
     * The default implementation is equivalent to:
     * <pre>{@code
     *     try (this) {
     *         while (hasNext())
     *             action.accept(next());
     *     }
     * }</pre>
     * 
     * @param action the action to be performed for each bytebuffer
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
        requireNonNull(action);
        try (this) {
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
     * @throws IOException {@inheritDoc}
     */
    @Override
    default void close() throws IOException {
        // Empty
    }
}