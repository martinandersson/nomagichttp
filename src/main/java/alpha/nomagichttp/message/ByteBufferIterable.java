package alpha.nomagichttp.message;

import java.io.IOException;

/**
 * An {@code Iterable} of {@code ByteBuffer}s.<p>
 * 
 * This iterable is not backed by a closeable resource, otherwise, this type is
 * fully specified by its supertype {@link ResourceByteBufferIterable}.<p>
 * 
 * More specifically, this type erases the throws' clauses from methods
 * {@code iterator}, {@code length} and {@code isEmpty}, and it is hereby
 * documented that the method {@code ByteBufferIterator.close} is
 * no-operation.<p>
 * 
 * Even though not closeable; the iterator may still perform I/O operations.
 * Meaning that {@code next} and {@code forEachRemaining} may throw
 * {@link IOException}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public interface ByteBufferIterable extends ResourceByteBufferIterable
{
    /**
     * {@inheritDoc}
     * 
     * @return {@inheritDoc}
     */
    @Override
    ByteBufferIterator iterator();
    
    /**
     * {@inheritDoc}
     * 
     * @apiNote
     * {@inheritDoc}
     * 
     * @return {@inheritDoc}
     */
    @Override
    long length();
    
    /**
     * {@inheritDoc}
     * 
     * @implSpec
     * {@inheritDoc}
     * 
     * @return {@inheritDoc}
     */
    @Override
    default boolean isEmpty() {
        return length() == 0;
    }
}