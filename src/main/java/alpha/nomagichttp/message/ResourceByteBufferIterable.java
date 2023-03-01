package alpha.nomagichttp.message;

import java.io.IOException;

/**
 * An {@code Iterable} of {@code ByteBuffer}s, backed by a closeable
 * resource.<p>
 * 
 * Unlike what may generally be the case for iterables, the backing data
 * structure of a bytebuffer iterable, does not need to be a collection. It can
 * be a file, a socket, or a pizza oven. Notably, the iterable may not
 * even be finite; the iteration could go on forever.<p>
 * 
 * Iterables come in two major forms; regenerative and non-regenerative.<p>
 * 
 * Being regenerative means that new iterators are forever supported. They are
 * mostly derived from finite and repeatable sources. For example, each new
 * iterator of a response body may read bytes from a byte array or a backing
 * file. The {@link #length() length} is the number of bytes that each new
 * iterator will observe. Iteration does not drain or take bytes away from the
 * iterable. The length will only change if the contents of the source changes.
 * The empty state can over time toggle back and forth. A regenerative iterable
 * must support being iterated concurrently by multiple threads.<p>
 * 
 * Non-regenerative means that iterators drain bytes from a non-repeatable
 * source. For example, each new iterator of a request body is like reading from
 * an updating cursor position inside a segment of a byte stream (the socket).
 * Iteration will reduce the iterable's length, which will eventually become
 * empty and stay empty. Many iterations can come and go, but the implementation
 * is not required to support concurrent iterations.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public interface ResourceByteBufferIterable
{
    /**
     * Returns an iterator of bytebuffers.
     * 
     * @return an iterator of bytebuffers (never {@code null})
     * 
     * @throws IOException if an I/O error occurs
     *                     (presumably from opening the resource)
     */
    ByteBufferIterator iterator() throws IOException;
    
    /**
     * Returns the number of iterable bytes.<p>
     * 
     * This method returns {@code -1} if the length is unknown, meaning that an
     * iteration can observe any number of bytes, from none at all to infinite.
     * This is generally the case for codecs and streaming data.<p>
     * 
     * For a regenerative iterable with a known length ({@code >= 0}), this
     * method will return the same value each time it is called synchronously
     * in-between {@code iterator} and {@code ByteBufferIterator.close}.
     * Otherwise, the returned value may be immediately outdated. For example,
     * the size of a file. But, an iterable backed by a file must hold a file
     * lock during iteration.<p>
     * 
     * For a non-regenerative iterable with a known length, the value is dynamic
     * and will over the course of iteration be reduced. For such kind of
     * iterable, this method semantically returns the number of
     * <i>remaining</i> bytes.<p>
     * 
     * Use cases of this method is for a consumer of a request body to
     * pre-allocate a destination sink, or for the server to set a
     * Content-Length header on an outgoing response.
     * 
     * @apiNote
     * Although the verbiage request/response "body size" is the more common
     * form on the internet, the NoMagicHTTP library prefers to be aligned with
     * <a href="https://www.rfc-editor.org/rfc/rfc7230#section-3.3.3">RFC 7230</a> 
     * and Java itself (e.g. array.length).
     * 
     * @return the number of iterable bytes
     * 
     * @throws IOException if an I/O error occurs
     */
    long length() throws IOException;
    
    /**
     * Returns true if this iterable is known to be empty.<p>
     * 
     * As is the case with {@link #length()}, the returned value may be
     * immediately outdated, unless called synchronously in-between
     * {@code iterator} and {@code ByteBufferIterator.close}.
     * 
     * @implSpec
     * The default implementation is equivalent to:
     * <pre>{@code
     *   return length() == 0;
     * }</pre>
     * 
     * @return see JavaDoc
     * 
     * @throws IOException if an I/O error occurs
     */
    default boolean isEmpty() throws IOException {
        return length() == 0;
    }
}