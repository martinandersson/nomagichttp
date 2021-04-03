package alpha.nomagichttp.util;

import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.message.Response;

import java.io.FileNotFoundException;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

import static java.net.http.HttpRequest.BodyPublisher;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Mirrors the API of {@link BodyPublishers} with implementations better in some
 * aspects.<p>
 * 
 * When this class offers an alternative, then it is safe to assume that the
 * alternative is a better choice, for at least one or all of the following
 * reasons: the alternative 1) is likely more performant with better memory
 * utilization (e.g. wrap data array on-demand instead of eager copying), 2) is
 * thread-safe and non-blocking, 3) has a documented contract and is more
 * compliant with the
 * <a href="https://github.com/reactive-streams/reactive-streams-jvm/blob/v1.0.3/README.md">
 * Reactive Streams</a> specification (same semantics as specified in {@link
 * Publishers} apply).<p>
 * 
 * When this class does not offer an alternative, then it is safe to assume that
 * the standard {@code BodyPublishers} factory is adequate or an alternative is
 * just not meaningful to implement. For example, {@link
 * BodyPublishers#ofInputStream(Supplier)} is by definition blocking and should
 * be avoided altogether.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Response.Builder#body(Flow.Publisher) 
 */
public final class BetterBodyPublishers
{
    /**
     * Maximum bytebuffer capacity.<p>
     * 
     * The value is the same as {@code
     * jdk.internal.net.http.common.Utils.DEFAULT_BUFSIZE}.<p>
     * 
     * Package visibility for tests.
     */
    static final int BUF_SIZE = 16 * 1_024;
    
    private BetterBodyPublishers() {
        // Empty
    }
    
    /**
     * Returns a body publisher whose body is the given {@code String},
     * converted using the {@link StandardCharsets#UTF_8 UTF_8} character set.
     * 
     * Is an alternative to {@link BodyPublishers#ofString(String)} except
     * without thread-safety issues (
     * <a href="https://bugs.openjdk.java.net/browse/JDK-8222968">JDK-8222968</a>).
     * 
     * @param   body the String containing the body
     * @return  a BodyPublisher
     * @throws  NullPointerException if {@code body} is {@code null}
     */
    public static BodyPublisher ofString(String body) {
        return ofString(body, UTF_8);
    }
    
    /**
     * Returns a request body publisher whose body is the given {@code
     * String}, converted using the given character set.
     * 
     * Is an alternative to {@link BodyPublishers#ofString(String, Charset)}
     * except without thread-safety issues (
     * <a href="https://bugs.openjdk.java.net/browse/JDK-8222968">JDK-8222968</a>).
     * 
     * @param   s the String containing the body
     * @param   charset the character set to convert the string to bytes
     * @return  a BodyPublisher
     * @throws  NullPointerException if any argument is {@code null}
     */
    public static BodyPublisher ofString(String s, Charset charset) {
        return ofByteArray(s.getBytes(charset));
    }
    
    /**
     * Returns a body publisher whose body is the given byte array.<p>
     * 
     * Is an alternative to {@link BodyPublishers#ofByteArray(byte[])} except
     * without thread-safety issues (
     * <a href="https://bugs.openjdk.java.net/browse/JDK-8222968">JDK-8222968</a>).<p>
     * 
     * The given data array is <i>not</i> copied. It should not be modified
     * after calling this method.
     * 
     * @param   buf the byte array containing the body
     * @return  a BodyPublisher
     * @throws  NullPointerException if {@code buf} is {@code null}
     */
    public static BodyPublisher ofByteArray(byte[] buf) {
        return ofByteArray(buf, 0, buf.length);
    }
    
    /**
     * Returns a body publisher whose body is the content of the given byte
     * array of {@code length} bytes starting from the specified {@code offset}.
     * 
     * Is an alternative to {@link BodyPublishers#ofByteArray(byte[], int, int)}
     * except without thread-safety issues (
     * <a href="https://bugs.openjdk.java.net/browse/JDK-8222968">JDK-8222968</a>).<p>
     * 
     * The given data array is <i>not</i> copied. It should not be modified
     * after calling this method.
     * 
     * @param   buf the byte array containing the body
     * @param   offset the offset of the first byte
     * @param   length the number of bytes to use
     * @return  a BodyPublisher
     * 
     * @throws NullPointerException
     *             if {@code buf} is {@code null}
     * 
     * @throws IndexOutOfBoundsException
     *             if the sub-range is defined to be out of bounds
     */
    public static BodyPublisher ofByteArray(byte[] buf, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, buf.length);
        return new Adapter(
                length - offset,
                Publishers.ofIterable(new ByteBufferIterable(buf, offset, length)));
    }
    
    /**
     * A body publisher that takes data from the contents of a file.<p>
     * 
     * Is an alternative to {@link BodyPublishers#ofFile(Path)} except the
     * implementation does not block and exceptions (e.g. {@link
     * FileNotFoundException}) are delivered to the [server's] subscriber, i.e.,
     * can be dealt with globally using an {@link ErrorHandler}.
     * 
     * @param   path the path to the file containing the body
     * @return  a BodyPublisher
     * @throws  AbstractMethodError for now (method not implemented)
     */
    public static BodyPublisher ofFile(Path path) {
        throw new AbstractMethodError("Implement me");
    }
    
    private static class Adapter implements BodyPublisher {
        private final long length;
        private final Flow.Publisher<? extends ByteBuffer> delegate;
        
        Adapter(long length, Flow.Publisher<? extends ByteBuffer> delegate) {
            this.length = length;
            this.delegate = delegate;
        }
        
        @Override
        public long contentLength() {
            return length;
        }
    
        @Override
        public void subscribe(Flow.Subscriber<? super ByteBuffer> s) {
            delegate.subscribe(s);
        }
    }
    
    private static class ByteBufferIterable implements Iterable<ByteBuffer>
    {
        private final byte[] buf;
        private final int offset;
        private final int length;
        
        ByteBufferIterable(byte[] buf, int offset, int length) {
            this.buf = buf;
            this.offset = offset;
            this.length = length;
        }
        
        @Override
        public Iterator<ByteBuffer> iterator() {
            return new It();
        }
        
        private class It implements Iterator<ByteBuffer> {
            private int pos = offset;
            
            @Override
            public boolean hasNext() {
                return desire() > 0;
            }
            
            @Override
            public ByteBuffer next() {
                final int cap = Math.min(BUF_SIZE, desire());
                final ByteBuffer bb = ByteBuffer.wrap(buf, pos, cap);
                pos += cap;
                return bb;
            }
            
            private int desire() {
                return length - pos;
            }
        }
    }
}