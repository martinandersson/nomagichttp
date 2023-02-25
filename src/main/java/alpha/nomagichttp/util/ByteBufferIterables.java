package alpha.nomagichttp.util;

import alpha.nomagichttp.message.ByteBufferIterable;
import alpha.nomagichttp.message.ByteBufferIterator;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.ResourceByteBufferIterable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import java.util.stream.Stream;

import static alpha.nomagichttp.util.Blah.addExactOrMaxValue;
import static alpha.nomagichttp.util.Blah.getOrCloseResource;
import static alpha.nomagichttp.util.Streams.stream;
import static java.nio.ByteBuffer.allocateDirect;
import static java.nio.channels.FileChannel.open;
import static java.nio.charset.CodingErrorAction.REPORT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.READ;
import static java.util.Objects.requireNonNull;

/**
 * Factories for creating bytebuffer iterables.<p>
 * 
 * The produced iterables are re-generative and suitable as response bodies (see
 * JavaDoc of {@link ResourceByteBufferIterable}).<p>
 * 
 * Unless documented differently, the iterables produced by this class do not
 * yield back unconsumed bytes. In other words; each call to {@code next}
 * returns a new bytebuffer whether the previous bytebuffer was fully consumed.
 * The implication is that the consumer can use bulk methods or access a backing
 * array without updating the position.<p>
 * 
 * All iterated bytebuffers are read-only.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class ByteBufferIterables
{
    private static final System.Logger LOG
            = System.getLogger(ByteBufferIterables.class.getPackageName());
    
    private ByteBufferIterables() {
        // Empty
    }
    
    /**
     * Returns an empty {@code ByteBufferIterable}.
     * 
     * @return see JavaDoc
     */
    public static ByteBufferIterable empty() {
        return Empty.INSTANCE;
    }
    
    /**
     * Returns a {@code ByteBufferIterable} backed by byte arrays.<p> 
     * 
     * The iterable's bytebuffers read from the given byte arrays, they are not
     * copied. The application should not modify the byte arrays after having
     * called this method.
     * 
     * @param first bytearray
     * @param more bytearrays
     * 
     * @return see JavaDoc
     * 
     * @throws NullPointerException
     *             if any argument is {@code null}
     */
    public static ByteBufferIterable just(byte[] first, byte[]... more) {
        return new OfByteBuffers(stream(first, more).map(ByteBuffer::wrap));
    }
    
    /**
     * Returns an iterable of the given bytebuffers.<p> 
     * 
     * The iterable's content is the given bytebuffers, they are not copied. The
     * application should not modify the bytebuffers after having called this
     * method.
     * 
     * @param first bytebuffer
     * @param more bytebuffers
     * 
     * @return see JavaDoc
     * 
     * @throws NullPointerException
     *             if any argument is {@code null}
     */
    public static ByteBufferIterable just(ByteBuffer first, ByteBuffer... more) {
        return new OfByteBuffers(stream(first, more));
    }
    
    /**
     * Returns an iterable of a {@code String} as encoded bytes.<p>
     * 
     * This method is equivalent to:
     * <pre>
     *   {@link #ofString(String, Charset) ofString
     *   }(str, {@link StandardCharsets#UTF_8 UTF_8})
     * </pre>
     * 
     * @param str to be encoded
     * 
     * @return see JavaDoc
     * 
     * @throws NullPointerException
     *             if {@code str} is {@code null}
     * @throws CharacterCodingException
     *             if input is malformed, or
     *             if a character is unmappable
     */
    public static ByteBufferIterable ofString(String str)
            throws CharacterCodingException {
        return ofString(str, UTF_8);
    }
    
    /**
     * Returns an iterable of a {@code String} as encoded bytes.
     * 
     * @param str to be encoded
     * @param cs to use for encoding
     * 
     * @return see JavaDoc
     * 
     * @throws NullPointerException
     *             if {@code str} is {@code null}
     * @throws CharacterCodingException
     *             if input is malformed, or
     *             if a character is unmappable
     */
    public static ByteBufferIterable ofString(String str, Charset cs)
            throws CharacterCodingException {
        var buf = cs.newEncoder()
                    .onMalformedInput(REPORT)
                    .onUnmappableCharacter(REPORT)
                    .encode(CharBuffer.wrap(requireNonNull(str)));
        final byte[] bytes;
        if (buf.hasArray() &&
            buf.arrayOffset() == 0 &&
            buf.array().length == buf.remaining()) {
            bytes = buf.array();
        } else {
            bytes = new byte[buf.remaining()];
            buf.get(bytes);
        }
        return just(bytes);
    }
    
    /**
     * Returns an iterable of a file.<p>
     * 
     * This method does not require the file to exist, nor does it require the
     * file to be non-empty. The file should exist, however, no later than when
     * an iterator is created.<p>
     * 
     * When an iterator is created, a new file channel is opened (for reading)
     * and a shared lock will be acquired using the method
     * {@link FileChannel#lock(long, long, boolean)} where the position is
     * specified as {@code 0} and the size is specified as
     * {@code Long.MAX_VALUE}.<p>
     * 
     * As is the case with {@link Request.Body}, the returned iterable uses only
     * one underlying bytebuffer for read operations and will yield back
     * unconsumed bytes. Same requirements apply; the bytebuffer should be
     * partially or fully consumed at once using relative get or relative bulk
     * get methods.
     * 
     * @param file path to read bytes from
     * 
     * @return see JavaDoc
     * 
     * @throws NullPointerException
     *             if {@code file} is {@code null}
     */
    public static ResourceByteBufferIterable ofFile(Path file) {
        return new OfFile(file);
    }
    
    /**
     * Returns an iterable generating bytebuffers from the given supplier.<p>
     * 
     * Each new iterator will pull the supplier at least once, until an empty
     * bytebuffer is returned.<p>
     * 
     * The supplier must be thread-safe.
     * 
     * @param s supplier of the iterable's content
     * 
     * @return see JavaDoc
     * 
     * @throws NullPointerException
     *             if {@code s} is {@code null}
     */
    public static ByteBufferIterable ofSupplier(
            Throwing.Supplier<ByteBuffer, ? extends IOException> s) {
        return new OfSupplier(s);
    }
    
    
    
    // ---------------
    // Implementations
    //  --------------
    
    private enum Empty implements ByteBufferIterable {
        INSTANCE;
        
        @Override
        public ByteBufferIterator iterator() {
            return ByteBufferIterator.Empty.INSTANCE;
        }
        
        @Override
        public long length() {
            return 0;
        }
    }
    
    private static class OfByteBuffers implements ByteBufferIterable {
        private final ByteBuffer[] bufs;
        // Computed eagerly, otherwise it would have to be volatile (word tearing)
        private final long len;
        
        OfByteBuffers(Stream<ByteBuffer> content) {
            bufs = content.filter(ByteBuffer::hasRemaining)
                          .toArray(ByteBuffer[]::new);
            long len = 0;
            for (var b : bufs) {
                len = addExactOrMaxValue(len, b.remaining());
            }
            this.len = len;
        }
        
        @Override
        public ByteBufferIterator iterator() {
            return len == 0 ?
                    ByteBufferIterator.Empty.INSTANCE :
                    new Iterator();
        }
        
        @Override
        public long length() {
            return len;
        }
        
        private class Iterator implements ByteBufferIterator {
            private int idx = 0;
            
            @Override
            public boolean hasNext() {
                return idx < bufs.length;
            }
            
            @Override
            public ByteBuffer next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return bufs[++idx].asReadOnlyBuffer();
            }
        }
    }
    
    private static class OfFile implements ResourceByteBufferIterable {
        // Same as jdk.internal.net.http.common.Utils.DEFAULT_BUFSIZE
        // (used by JDK's BodyPublishers.ofFile(), and is HTTP/2 max frame size)
        private static final int BUF_SIZE = 16 * 1_024;
        
        private final Path file;
        
        OfFile(Path file) {
            this.file = requireNonNull(file);
        }
        
        @Override
        public ByteBufferIterator iterator() throws IOException {
            return new Iterator();
        }
        
        @Override
        public long length() throws IOException {
            return Files.size(file);
        }
        
        private class Iterator implements ByteBufferIterator {
            private final FileChannel ch;
            private final ByteBuffer buf, view;
            private final long len;
            private long count;
            
            Iterator() throws IOException {
                var ch = acquireSharedLock(open(file, READ));
                long len = getOrCloseResource(ch::size, ch);
                this.buf = allocateDirect(BUF_SIZE);
                // The view is created with no remaining to force-read on first
                // call to next()
                this.view = buf.asReadOnlyBuffer().position(BUF_SIZE);
                this.ch = ch;
                this.len = len;
                this.count = 0;
            }
            
            private static FileChannel acquireSharedLock(FileChannel ch)
                    throws IOException
            {
                var lock = getOrCloseResource(() ->
                        ch.lock(0, Long.MAX_VALUE, true), ch);
                if (!lock.isShared()) {
                    // TODO: Config.acceptExclusiveFileLock() ??
                    // (don't want to force-check this on JVM startup; we may never serve files)
                    throw new UnsupportedOperationException(
                        "No operating system support for shared file locks.");
                }
                return ch;
            }
            
            @Override
            public boolean hasNext() {
                return view.hasRemaining() || desireInt() > 0;
            }
            
            @Override
            public ByteBuffer next() throws IOException {
                if (view.hasRemaining()) {
                    return view;
                }
                final int d = desireInt();
                if (d == 0) {
                    throw new NoSuchElementException();
                }
                // Not public:
                //     requireVirtualThread()
                // But does not matter; is called by the ChannelReader
                clearAndLimitBuffers(d);
                int v = ch.read(buf);
                assert v != -1 : "End-Of-Stream not expected";
                assert v > 0 : "We had some desire left";
                count = addExactOrMaxValue(count, v);
                view.limit(buf.position());
                assert view.hasRemaining();
                return view;
            }
            
            private int desireInt() {
                final long d = len - count;
                assert d >= 0L : "Weird to have negative cravings";
                try {
                    return Math.toIntExact(d);
                } catch (ArithmeticException e) {
                    return Integer.MAX_VALUE;
                }
            }
            
            private void clearAndLimitBuffers(int desire) {
                buf.clear();
                view.clear();
                if (desire < buf.capacity()) {
                    buf.limit(desire);
                    view.limit(desire);
                }
            }
            
            @Override
            public void close() throws IOException {
                ch.close();
            }
        }
    }
    
    private static class OfSupplier implements ByteBufferIterable {
        private final Throwing.Supplier<ByteBuffer, ? extends IOException> s;
        
        OfSupplier(Throwing.Supplier<ByteBuffer, ? extends IOException> s) {
            this.s = requireNonNull(s);
        }
        
        @Override
        public ByteBufferIterator iterator() {
            return new Iterator();
        }
        
        @Override
        public long length() {
            return -1;
        }
        
        private class Iterator implements ByteBufferIterator {
            private boolean eos;
            
            @Override
            public boolean hasNext() {
                return !eos;
            }
            
            @Override
            public ByteBuffer next() throws IOException {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                var buf = s.get();
                if (!buf.hasRemaining()) {
                    eos = true;
                }
                return buf.isReadOnly() ?
                        buf : buf.asReadOnlyBuffer();
            }
        }
    }
}