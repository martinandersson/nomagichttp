package alpha.nomagichttp.util;

import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.message.Response;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Flow;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static alpha.nomagichttp.util.PushPullUnicastPublisher.nonReusable;
import static alpha.nomagichttp.util.Streams.stream;
import static java.lang.Long.MAX_VALUE;
import static java.lang.Long.MIN_VALUE;
import static java.lang.System.Logger.Level.DEBUG;
import static java.net.http.HttpRequest.BodyPublisher;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/**
 * Mirrors the API of {@link BodyPublishers} with implementations better in some
 * aspects.<p>
 * 
 * All publishers produced by this class follows the same semantics specified in
 * {@link Publishers}.<p>
 * 
 * When this class offers an alternative, then it is safe to assume that the
 * alternative is a better choice, for at least one or both of the following
 * reasons: the alternative is 1) likely more efficient with CPU and memory
 * (e.g. wrap data array on-demand instead of eager copying), 2) is thread-safe,
 * 3) is non-blocking.<p>
 * 
 * The alternative is also more compliant with the
 * <a href="https://github.com/reactive-streams/reactive-streams-jvm/blob/v1.0.3/README.md">
 * Reactive Streams</a> specification. This is not necessarily a good thing lol,
 * but at least the contract is well documented (see {@link Publishers}) and so
 * there will be no surprises for the application developer.<p>
 * 
 * When this class does not offer an alternative, then either the {@code
 * BodyPublishers} factory is too recent to have been ported, or it is adequate,
 * or an alternative is just not meaningful to implement (for example,
 * {@link BodyPublishers#ofInputStream(Supplier)} is by definition blocking and
 * should be avoided altogether).<p>
 * 
 * Please do not be misled by the Java namespace for
 * <i>{@code HttpRequest}</i>{@code .BodyPublisher}. The {@code BodyPublisher}
 * is simply a publisher with a known content length used in turn by the HTTP
 * protocol for message framing. Obviously this is useful not just for requests
 * but for responses as well.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Response.Builder#body(Flow.Publisher)
 */
public final class BetterBodyPublishers
{
    private static final System.Logger LOG
            = System.getLogger(BetterBodyPublishers.class.getPackageName());
    
    /**
     * Maximum bytebuffer capacity.<p>
     * 
     * The value is the same as {@code
     * jdk.internal.net.http.common.Utils.DEFAULT_BUFSIZE}.<p>
     * 
     * Package visibility for tests.
     */
    // Also same as ChannelByteBufferPublisher.BUF_SIZE
    static final int BUF_SIZE = 16 * 1_024;
    
    private BetterBodyPublishers() {
        // Empty
    }
    
    /**
     * Returns a body publisher whose body is the given {@code String},
     * converted using the {@link StandardCharsets#UTF_8 UTF_8} character
     * set.<p>
     * 
     * Is an alternative to {@link BodyPublishers#ofString(String)} except
     * it is likely more performant and has no thread-safety issues.<p>
     * 
     * Published bytebuffers are read-only.
     * 
     * @param  str the String containing the body
     * @return a BodyPublisher
     * @throws NullPointerException if {@code body} is {@code null}
     */
    public static BodyPublisher ofString(String str) {
        return ofString(str, UTF_8);
    }
    
    /**
     * Returns a request body publisher whose body is the given {@code
     * String}, converted using the given character set.<p>
     * 
     * This method is an alternative to {@link
     * BodyPublishers#ofString(String, Charset)} except it is likely more
     * performant and has no thread-safety issues.<p>
     * 
     * Published bytebuffers are read-only.
     * 
     * @param  str the String containing the body
     * @param  charset the character set to convert the string to bytes
     * @return a BodyPublisher
     * @throws NullPointerException if any argument is {@code null}
     */
    public static BodyPublisher ofString(String str, Charset charset) {
        return ofByteArray(str.getBytes(charset));
    }
    
    /**
     * Returns a body publisher of bytes from an array.<p>
     * 
     * This method is an alternative to {@link
     * BodyPublishers#ofByteArray(byte[])}  except it is likely more performant
     * and has no thread-safety issues.<p>
     * 
     * The given data array is <i>not</i> defensively copied. It should not be
     * modified after calling this method.<p>
     * 
     * Published bytebuffers are read-only.
     * 
     * @param  buf the byte array containing the body
     * @return a BodyPublisher
     * @throws NullPointerException if {@code buf} is {@code null}
     */
    public static BodyPublisher ofByteArray(byte[] buf) {
        return ofByteArray(buf, 0, buf.length);
    }
    
    /**
     * Returns a body publisher of bytes from an array.<p>
     *
     * This method is an alternative to {@link
     * BodyPublishers#ofByteArray(byte[], int, int)} except it is likely more
     * performant and has no thread-safety issues.<p>
     * 
     * The given data array is <i>not</i> defensively copied. It should not be
     * modified after calling this method.<p>
     * 
     * Published bytebuffers are read-only.
     * 
     * @param  buf the byte array containing the body
     * @param  offset the offset of the first byte
     * @param  length the number of bytes to use
     * @return a BodyPublisher
     * 
     * @throws NullPointerException
     *             if {@code buf} is {@code null}
     * 
     * @throws IndexOutOfBoundsException
     *             if the sub-range is defined to be out of bounds
     */
    public static BodyPublisher ofByteArray(byte[] buf, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, buf.length);
        return asBodyPublisher(
                Publishers.ofIterable(new ByteBufferIterable(buf, offset, length)),
                length);
    }
    
    /**
     * Wraps the given delegate with a {@code contentLength} set to -1 (unknown).
     * 
     * @param delegate upstream source of bytebuffers
     * 
     * @return a new body publisher
     * 
     * @throws NullPointerException if {@code delegate} is {@code null}
     */
    public static BodyPublisher asBodyPublisher(Flow.Publisher<? extends ByteBuffer> delegate) {
        return asBodyPublisher(delegate, -1);
    }
    
    /**
     * Wraps the given delegate with a content-length.
     * 
     * @param delegate upstream source of bytebuffers
     * @param contentLength content length (byte count)
     * 
     * @return a new body publisher
     * 
     * @throws NullPointerException if {@code delegate} is {@code null}
     */
    public static BodyPublisher asBodyPublisher(
            Flow.Publisher<? extends ByteBuffer> delegate, long contentLength)
    {
        return new Adapter(delegate, contentLength);
    }
    
    /**
     * Returns a body publisher of a file.<p>
     * 
     * This method works like this:
     * <pre>
     *   {@link #ofFile(Path) ofFile}(Paths.{@link Paths#get(String, String...) get}(path, more))
     * </pre>
     * 
     * @param path the path or initial part of the path to the file
     * @param more more path parts
     * @return a BodyPublisher
     */
    public static BodyPublisher ofFile(String path, String... more) {
        return ofFile(Paths.get(path, more));
    }
    
    /**
     * Returns a body publisher of a file.<p>
     * 
     * The length of the file will be read only once when the first subscriber
     * subscribes. If the file does not exist at this time, the content length
     * will be set to {@code -1} (unknown). The file must exist no later than
     * when the first subscriber requests items.<p>
     * 
     * It is important that the file size does not change while a subscription
     * is active, or after a known file size has been acquired if future
     * subscribers are expected. Post-modifications to the file size may lead to
     * invalid HTTP message framing with undefined application behavior as a
     * consequence.<p>
     * 
     * This method is an alternative to {@link BodyPublishers#ofFile(Path)}
     * except the implementation does not block and exceptions like {@link
     * FileNotFoundException} are delivered to the subscriber, i.e., the HTTP
     * server itself, and therefore be dealt with globally using an
     * {@link ErrorHandler}.
     * 
     * @param path the path to the file containing the body
     * @return a BodyPublisher
     */
    public static BodyPublisher ofFile(Path path) {
        LongSupplier len = () -> {
            try {
                return Files.size(path);
            } catch (IOException e) {
                // Even on FileNotFoundException we return -1
                // (file may exist when the subscription starts)
                return -1;
            }
        };
        return new Adapter(new FilePublisher(path), len);
    }
    
    /**
     * Equivalent to {@link
     * Publishers#concat(Flow.Publisher, Flow.Publisher, Flow.Publisher[])},
     * except the publisher returned is a {@link BodyPublisher}.<p>
     * 
     * The returned publisher will have {@link BodyPublisher#contentLength()}
     * set to the sum of all lengths provided by the given publishers, capped at
     * {@code MAX_VALUE}, only if all publishers are instances of BodyPublisher
     * with a non-negative length, otherwise the length will be set to {@code
     * -1} (unknown length).
     * 
     * @param first publisher
     * @param second publisher
     * @param more optionally
     * 
     * @return all given publishers orderly concatenated into one
     * @throws NullPointerException if any arg or array element is {@code null}
     */
    @SafeVarargs
    @SuppressWarnings("varargs")
    public static BodyPublisher concat(
            Flow.Publisher<ByteBuffer> first,
            Flow.Publisher<ByteBuffer> second,
            Flow.Publisher<ByteBuffer>... more)
    {
        class Negative extends RuntimeException {
            // For compilation warning
            // "[serial] serializable class Negative has no definition of serialVersionUID"
            private static final long serialVersionUID = 1L;
        }
        long len;
        try {
            len = stream(first, second, more)
                    .mapToLong(p -> p instanceof BodyPublisher ?
                        ((BodyPublisher) p).contentLength() : -1 )
                    .peek(v -> { if (v < 0)
                        throw new Negative(); })
                    .reduce(0, (a, b) -> {
                        try {
                            return Math.addExact(a, b);
                        } catch (ArithmeticException e) {
                            return MAX_VALUE;
                        }
                    });
        } catch (Negative e) {
            len = -1;
        }
        
        return asBodyPublisher(Publishers.concat(first, second, more), len);
    }
    
    private static class Adapter implements BodyPublisher {
        private final Flow.Publisher<? extends ByteBuffer> delegate;
        private final LongSupplier length;
        
        Adapter(Flow.Publisher<? extends ByteBuffer> delegate, long length) {
            this(delegate, () -> length);
        }
        
        Adapter(Flow.Publisher<? extends ByteBuffer> delegate, LongSupplier length) {
            this.length   = requireNonNull(length);
            this.delegate = requireNonNull(delegate);
        }
        
        private long len = MIN_VALUE;
        
        @Override
        public long contentLength() {
            var l = len;
            return l == MIN_VALUE ? (len = length.getAsLong()) : l;
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
        private final int end;
        
        ByteBufferIterable(byte[] buf, int offset, int length) {
            this.buf    = buf;
            this.offset = offset;
            this.end    = offset + length;
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
                final ByteBuffer bb = ByteBuffer.wrap(buf, pos, cap).asReadOnlyBuffer();
                pos += cap;
                return bb;
            }
            
            private int desire() {
                return end - pos;
            }
        }
    }
    
    private static final class FilePublisher implements Flow.Publisher<ByteBuffer> {
        private static final int MAX_READ_AHEAD = 3;
        private static final ByteBuffer EOS = ByteBuffer.allocate(0);
        
        private final Path path;
        
        FilePublisher(Path path) {
            this.path = requireNonNull(path);
        }
        
        @Override
        public void subscribe(Flow.Subscriber<? super ByteBuffer> s) {
            new Reader(path).subscribe(s);
        }
        
        private static final class Reader implements Flow.Publisher<ByteBuffer> {
            private final Path path;
            private final PushPullUnicastPublisher<ByteBuffer> announcer;
            private final Deque<ByteBuffer> contents;
            private final Handler handler;
            private AsynchronousFileChannel fc;
            
            Reader(Path path) {
                this.path      = path;
                this.announcer = nonReusable(this::getNext, this::closeSafe);
                this.contents  = new ConcurrentLinkedDeque<>();
                this.handler   = new Handler();
                this.fc        = null;
            }
            
            @Override
            public void subscribe(Flow.Subscriber<? super ByteBuffer> s) {
                announcer.subscribe(s);
            }
            
            private ByteBuffer getNext() {
                if (fc == null && !open()) {
                    return null;
                }
                ByteBuffer b = contents.poll();
                if (b == EOS) {
                    announcer.complete();
                    closeSafe();
                    return null;
                }
                handler.tryScheduleRead();
                return b;
            }
            
            private boolean open() {
                try {
                    fc = AsynchronousFileChannel.open(path);
                    return true;
                } catch (UnsupportedOperationException | IOException | SecurityException e) {
                    announcer.error(e);
                    return false;
                }
            }
            
            private void closeSafe() {
                if (fc != null) {
                    try {
                        fc.close();
                    } catch (ClosedChannelException e) {
                        LOG.log(DEBUG, "File channel already closed.");
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                contents.clear();
            }
            
            private class Handler implements CompletionHandler<Integer, ByteBuffer> {
                private final SeriallyRunnable op = new SeriallyRunnable(this::initiate, true);;
                private boolean eos;
                private int pos;
                
                void tryScheduleRead() {
                    if (contents.size() < MAX_READ_AHEAD) {
                        op.run();
                    }
                }
                
                private void initiate() {
                    if (eos) {
                        return;
                    }
                    ByteBuffer b = ByteBuffer.allocateDirect(BUF_SIZE);
                    try {
                        fc.read(b, pos, b, this);
                    } catch (Throwable t) {
                        failed(t, null);
                        throw t;
                    }
                }
                
                @Override
                public void completed(Integer res, ByteBuffer b) {
                    if (res == -1) {
                        eos = true;
                        contents.add(EOS);
                    } else {
                        b.flip();
                        pos += b.remaining();
                        contents.add(b);
                    }
                    announcer.announce();
                    op.complete();
                    if (!eos) {
                        tryScheduleRead();
                    }
                }
                
                @Override
                public void failed(Throwable exc, ByteBuffer ignored) {
                    announcer.error(exc);
                    closeSafe();
                }
            }
        }
    }
}