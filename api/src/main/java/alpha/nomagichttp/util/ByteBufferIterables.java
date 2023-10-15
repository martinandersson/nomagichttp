package alpha.nomagichttp.util;

import alpha.nomagichttp.ChannelWriter;
import alpha.nomagichttp.Config;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.message.ByteBufferIterable;
import alpha.nomagichttp.message.ByteBufferIterator;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.ResourceByteBufferIterable;
import alpha.nomagichttp.message.Response;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static alpha.nomagichttp.util.Blah.addExactOrCap;
import static alpha.nomagichttp.util.Blah.getOrClose;
import static alpha.nomagichttp.util.Blah.throwsNoChecked;
import static alpha.nomagichttp.util.Blah.toNanosOrMaxValue;
import static alpha.nomagichttp.util.ByteBuffers.asArray;
import static alpha.nomagichttp.util.ScopedValues.httpServer;
import static alpha.nomagichttp.util.Streams.stream;
import static java.nio.ByteBuffer.allocateDirect;
import static java.nio.channels.FileChannel.open;
import static java.nio.charset.CodingErrorAction.REPLACE;
import static java.nio.charset.CodingErrorAction.REPORT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.READ;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Factories for creating bytebuffer iterables.<p>
 * 
 * All iterables produced by this class are suitable to be used as a response
 * body.<p>
 * 
 * All iterated bytebuffers are read-only.<p>
 * 
 * Almost all iterables created by this class are regenerative and thread-safe,
 * the iterator is also thread-safe. Meaning that the iterable can be cached
 * globally and shared across different responses, and the encapsulating
 * responses can also be cached globally and reused. The one exception to this
 * rule is {@link #ofSupplier(Throwing.Supplier)}, which may or may not be
 * thread-safe; the semantics depends on the nature of its given data-supplying
 * function.<p>
 * 
 * Almost all iterables created by this class does not yield back unconsumed
 * bytes. In other words; each call to {@code next} returns a new bytebuffer,
 * regardless if the previous bytebuffer was fully consumed. One implication is
 * that the consumer can use bulk methods or access a backing array without
 * updating the position. The two exceptions to this rule is {@code ofSupplier}
 * (depends on the function) and all {@code ofFile} methods. The {@code ofFile}
 * iterable's iterator works much like the {@link Request.Body}; it uses only
 * one underlying bytebuffer for file read operations, and so same requirements
 * apply; the bytebuffer should be partially or fully consumed at once using
 * relative get or relative bulk get methods (which is what the
 * {@link ChannelWriter#write(Response) ChannelWriter} do; no sweat!).
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see ResourceByteBufferIterable
 */
public final class ByteBufferIterables
{
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
     * Returns an iterable backed by the given bytebuffers.<p> 
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
     * Returns an iterable of the given bytebuffers.<p> 
     * 
     * The iterable's content is the given bytebuffers, they are not copied. The
     * application should not modify the bytebuffers after having called this
     * method.
     * 
     * @param items backing byte arrays
     * 
     * @return see JavaDoc
     * 
     * @throws NullPointerException
     *             if {@code items} is {@code null}
     */
    public static ByteBufferIterable just(Iterable<ByteBuffer> items) {
        return new OfByteBuffers(
                StreamSupport.stream(items.spliterator(), false));
    }
    
    /**
     * Returns an iterable of a {@code String} encoded using UTF-8.
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
     * Returns an iterable of a {@code String} encoded using UTF-8.<p>
     * 
     * Malformed input and unmappable characters will be replaced. This should
     * not happen for most inputs. Nonetheless, specifying the string "\uD83F"
     * (illegal code point) will produce bytes that decoded using UTF-8 equals
     * the '?' character. Specifying the same string to
     * {@link #ofString(String) ofString} throws a
     * {@link MalformedInputException}. That is why this method should generally
     * not be used, except for cases when the range of input is known in
     * advance, such as in test cases, and even then, the only reason this
     * method offers a benefit is to not have to deal with a checked exception.
     * 
     * @param str to be encoded
     * 
     * @return see JavaDoc
     * 
     * @throws NullPointerException
     *             if {@code str} is {@code null}
     * 
     * @see <a href="https://stackoverflow.com/a/27781166">StackOverflow answer</a>
     */
    public static ByteBufferIterable ofStringUnsafe(String str) {
        var enc = UTF_8.newEncoder()
                       .onMalformedInput(REPLACE)
                       .onUnmappableCharacter(REPLACE);
        // No checked because of REPLACE (REPORT is what throws)
        return throwsNoChecked(() -> ofString(str, enc));
    }
    
    /**
     * Returns an iterable of an encoded {@code String}.
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
        return ofString(str, cs.newEncoder()
                               .onMalformedInput(REPORT)
                               .onUnmappableCharacter(REPORT));
    }
    
    /**
     * Returns an iterable of a {@code String} as encoded bytes.<p>
     * 
     * The method used for encoding is
     * {@link CharsetEncoder#encode(CharBuffer)}, which will reset the encoder
     * before an entire encoding operation takes place. The given encoder must
     * not be used concurrently.<p>
     * 
     * A {@link CharacterCodingException} is only thrown if the given encoder
     * is configured to use {@link CodingErrorAction#REPORT} and the string
     * contains bad input triggering the action.
     * 
     * @param str to be encoded
     * @param enc encoder to use
     * 
     * @return see JavaDoc
     * 
     * @throws NullPointerException
     *             if any argument is {@code null}
     * @throws CharacterCodingException
     *             may be thrown if input is malformed, or
     *             if a character is unmappable
     */
    public static ByteBufferIterable ofString(String str, CharsetEncoder enc)
            throws CharacterCodingException {
        var buf = enc.encode(CharBuffer.wrap(str));
        return just(asArray(buf));
    }
    
    /**
     * Returns an iterable of a file.<p>
     * 
     * An invocation of this method behaves in exactly the same way as the
     * invocation
     * <pre>
     *     ByteBufferIterables.{@link #ofFile(Path,long,TimeUnit)
     *       ofFile}(file, timeout, unit);
     * </pre>
     * where the values used for {@code timeout} and {@code unit} are derived
     * (at runtime/by iterator) from
     * <pre>
     *     {@link ScopedValues#httpServer() httpServer
     *     }().{@link HttpServer#getConfig() getConfig
     *     }().{@link Config#timeoutFileLock() timeoutFileLock}()
     * </pre>
     * 
     * @param file to read bytes from
     * 
     * @return see JavaDoc
     * 
     * @throws NullPointerException
     *             if {@code file} is {@code null}
     */
    public static ResourceByteBufferIterable ofFile(Path file) {
        return new OfFile(file, true);
    }
    
    /**
     * Returns an iterable of a file.<p>
     * 
     * This method does not require the file to exist. The file should exist,
     * however, no later than when an iterator is created; which is when the
     * implementation uses {@link FileChannel#open(Path, OpenOption...)} to open
     * the file for reading; which unfortunately does not specify what happens
     * if the file does not exist. It's probably safe to expect an {@code
     * IOException} at this point ({@link NoSuchFileException}, to be more
     * specific).<p>
     * 
     * The returned iterable's {@code iterator} method will try to acquire a
     * read-lock which will ensure that no other co-operating thread within the
     * currently running JVM can write to the same file at the same time;
     * concurrent reads are accepted (the {@code iterator} method will return
     * exceptionally if a lock can not be acquired). With being
     * <i>co-operative</i> means that any other concurrent thread must acquire a
     * lock for the same file before accessing it, using {@link JvmPathLock}
     * directly, or by calling a method that uses the {@code JvmPathLock},
     * which is what the {@code iterator} method does.<p>
     * 
     * The lock is unlocked no later than when the iterator's {@code close}
     * method is called.
     * 
     * @param file     to read bytes from
     * @param timeout  the time the iterator waits for a lock
     * @param unit     the time unit of the timeout argument
     * 
     * @return see JavaDoc
     * 
     * @throws NullPointerException
     *             if any argument is {@code null}
     */
    public static ResourceByteBufferIterable ofFile(
            Path file, long timeout, TimeUnit unit) {
        return new OfFile(file, timeout, unit);
    }
    
    /**
     * Returns an iterable of a file.<p>
     * 
     * An invocation of this method behaves in exactly the same way as the
     * invocation
     * <pre>
     *     ByteBufferIterables.{@link #ofFile(Path,long,TimeUnit)
     *       ofFile}(file, timeout, unit);
     * </pre>
     * except the iterator will <strong>not</strong> acquire any kind of lock;
     * not in the currently running JVM nor outside of it. The application has
     * the responsibility to co-ordinate file access; if warranted.
     * 
     * @param file to read bytes from
     * 
     * @return see JavaDoc
     * 
     * @throws NullPointerException
     *             if {@code file} is {@code null}
     * 
     * @see ResourceByteBufferIterable#length()
     */
    public static ResourceByteBufferIterable ofFileNoLock(Path file) {
        return new OfFile(file, false);
    }
    
    /**
     * Returns an iterable generating bytebuffers from the given supplier.<p>
     * 
     * Each new iterator will pull the supplier at least once, until an empty
     * bytebuffer is returned.<p>
     * 
     * This method is intended to be used for streaming response bodies.<p>
     * 
     * When used as a response body; the given function does not have to track
     * the consumption of and yield back bytebuffers that wasn't fully consumed,
     * because the channel writer will fully consume the bytebuffers using
     * relative get-methods.<p>
     * 
     * The iterable can be shared across different responses, and the
     * encapsulating response can also be cached globally and reused — only if
     * the supplier is thread-safe, and it yields thread-exclusive bytebuffers
     * on each invocation ({@code ByteBuffer} is not thread-safe). One way to
     * accomplish thread-safety out of a shared bytebuffer is to simply return
     * {@link ByteBuffer#asReadOnlyBuffer()}.
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
                len = addExactOrCap(len, b.remaining());
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
                return bufs[idx++].asReadOnlyBuffer();
            }
        }
    }
    
    private static class OfFile implements ResourceByteBufferIterable {
        // Same as jdk.internal.net.http.common.Utils.DEFAULT_BUFSIZE
        // (used by JDK's BodyPublishers.ofFile(), and is HTTP/2 max frame size)
        private static final int BUF_SIZE = 16 * 1_024;
        private final Path file;
        private final boolean useLock;
        private final Long timeout;
        private final TimeUnit unit;
        
        OfFile(Path file, boolean useLock) {
            this.file = requireNonNull(file);
            this.useLock = useLock;
            this.timeout = null;
            this.unit = null;
        }
        
        OfFile(Path file, long timeout, TimeUnit unit) {
            this.file = requireNonNull(file);
            this.useLock = true;
            this.timeout = timeout;
            this.unit = requireNonNull(unit);
        }
        
        @Override
        public ByteBufferIterator iterator()
                throws InterruptedException, FileLockTimeoutException, IOException {
            return new Iterator();
        }
        
        @Override
        public long length() throws IOException {
            return Files.size(file);
        }
        
        private class Iterator implements ByteBufferIterator {
            private final ByteBuffer buf, view;
            private final JvmPathLock lck;
            private final FileChannel ch;
            private final long len;
            private long count;
            
            Iterator()
                  throws InterruptedException, FileLockTimeoutException, IOException {
                this.buf = allocateDirect(BUF_SIZE);
                // The view is created with no remaining to force-read on first
                // call to next()
                this.view = buf.asReadOnlyBuffer().position(BUF_SIZE);
                this.lck = useLock ? readLock() : null;
                this.ch = getOrClose(() -> open(file, READ), this);
                this.len = getOrClose(ch::size, this);
                this.count = 0;
            }
            
            private JvmPathLock readLock()
                    throws InterruptedException, FileLockTimeoutException {
                final Duration dur = timeout == null ?
                        httpServer().getConfig().timeoutFileLock() :
                        Duration.of(timeout, unit.toChronoUnit());
                return JvmPathLock.readLock(
                        file, toNanosOrMaxValue(dur), NANOSECONDS);
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
                assert v > 0 : "Should have read something";
                count = addExactOrCap(count, v);
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
                try {
                    // Can be null; see iterator()
                    if (ch != null) ch.close();
                } finally {
                    if (lck != null) lck.close();
                }
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