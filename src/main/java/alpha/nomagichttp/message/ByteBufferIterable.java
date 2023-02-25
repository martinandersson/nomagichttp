package alpha.nomagichttp.message;

/**
 * An {@code Iterable} of {@code ByteBuffer}s.<p>
 * 
 * This iterable is not backed by a closeable resource, otherwise, this type is
 * fully specified by its supertype {@link ResourceByteBufferIterable}.<p>
 * 
 * More specifically, this type erases the {@code throws IOException} clause
 * from the methods {@code iterator}, {@code length}, {@code isEmpty}, and it is
 * hereby documented that there is no use of the method
 * {@code ByteBufferIterator.close}.
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