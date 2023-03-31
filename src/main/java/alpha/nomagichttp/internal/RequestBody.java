package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.BadRequestException;
import alpha.nomagichttp.message.ByteBufferIterable;
import alpha.nomagichttp.message.ByteBufferIterator;
import alpha.nomagichttp.message.ContentHeaders;
import alpha.nomagichttp.message.MaxRequestBodyConversionSizeExceededException;
import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.UnsupportedTransferCodingException;
import alpha.nomagichttp.util.JvmPathLock;
import alpha.nomagichttp.util.Throwing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static alpha.nomagichttp.HttpConstants.HeaderName.CONTENT_LENGTH;
import static alpha.nomagichttp.HttpConstants.HeaderName.TRANSFER_ENCODING;
import static alpha.nomagichttp.internal.Blah.requireVirtualThread;
import static alpha.nomagichttp.util.Blah.EMPTY_BYTEARRAY;
import static alpha.nomagichttp.util.Blah.addExactOrCap;
import static alpha.nomagichttp.util.ScopedValues.httpServer;
import static java.lang.Long.MAX_VALUE;
import static java.nio.ByteBuffer.wrap;
import static java.nio.charset.CodingErrorAction.REPORT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Default implementation of {@code Request.Body}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class RequestBody implements Request.Body
{
    /**
     * Creates a request body.
     * 
     * @param headers of request
     * @param reader the channel
     * 
     * @return a request body
     * 
     * @throws BadRequestException
     *             on invalid message framing
     */
    static RequestBody of(ContentHeaders headers, ChannelReader reader) {
        var len = headers.contentLength();
        var enc = headers.transferEncoding();
        final ByteBufferIterable content;
        if (len.isPresent()) {
            if (!enc.isEmpty()) {
                throw new BadRequestException(
                    "%s and %s are both present.".formatted(
                    CONTENT_LENGTH, TRANSFER_ENCODING));
            }
            content = reader.limit(len.getAsLong());
        } else if (enc.isEmpty()) {
            // "If this is a request message [...] then"
            //  the message body length is zero (no message body is present)."
            // (only outbound responses may be close-delimited)
            // https://tools.ietf.org/html/rfc7230#section-3.3.3
            content = reader.limit(0);
        } else {
            for (var v : enc) {
                if (!v.equalsIgnoreCase("chunked")) {
                    throw new UnsupportedTransferCodingException(v);
                }
            }
            if (enc.size() > 1) {
                throw new BadRequestException(
                    "Chunked encoding applied multiple times");
            }
            content = new ChunkedDecoder(reader);
        }
        return new RequestBody(headers, content);
    }
    
    private final ContentHeaders headers;
    private final ByteBufferIterable content;
    private Throwing.Runnable<IOException> onConsumption;
    
    private RequestBody(ContentHeaders headers, ByteBufferIterable content) {
        this.headers  = headers;
        this.content = content;
        this.onConsumption = null;
    }
    
    /**
     * Schedules a callback to run just before request body consumption begins.
     * 
     * @param run the callback
     */
    void onConsumption(Throwing.Runnable<IOException> run) {
        assert onConsumption == null;
        this.onConsumption = run;
    }
    
    @Override
    public ByteBufferIterator iterator() {
        if (onConsumption != null && !isEmpty()) {
            return new FirstRespond100Continue(content.iterator());
        }
        return content.iterator();
    }
    
    @Override
    public CharSequence toCharSequence() throws IOException {
        /*
         According to String impl
            "These SD/E objects are short-lived, the young-gen gc should be able
             to take care of them well".
         Charset.decode() says it
            "is potentially more efficient because it can cache decoders between
             successive invocations".
         hmm. Well number two is out of the question, for virtual threads. */
        
        var decoder = headers.contentType()
                             .filter(m -> m.type().equals("text"))
                             .map(MediaType::parameters)
                             .map(p -> p.get("charset"))
                             .map(Charset::forName)
                             .orElse(UTF_8)
                             .newDecoder()
                             .onMalformedInput(REPORT)
                             .onUnmappableCharacter(REPORT);
        final byte[] raw = bytes();
        if (raw.length == 0) {
            return "";
        }
        return decoder.decode(wrap(raw));
    }
    
    @Override
    public long toFile(Path path, OpenOption... options)
            throws InterruptedException, TimeoutException, IOException {
        long nanos = MAX_VALUE;
        try {
            nanos = httpServer().getConfig().timeoutFileLock().toNanos();
        } catch (ArithmeticException useMaxVal) {}
        return toFile(path, nanos, NANOSECONDS, Set.of(options));
    }
    
    @Override
    public long toFile(
            Path path, long timeout, TimeUnit unit,
            Set<? extends OpenOption> opts, FileAttribute<?>... attrs)
            throws InterruptedException, TimeoutException, IOException
    {
        try (var lck = JvmPathLock.readLock(path, 1, SECONDS)) {
            return toFile0(path, opts, attrs);
        }
    }
    
    @Override
    public long toFileNoLock(
            Path path, OpenOption... opts) throws IOException {
        return toFile0(path, Set.of(opts));
    }
    
    @Override
    public long toFileNoLock(
            Path path, Set<? extends OpenOption> opts,
            FileAttribute<?>... attrs) throws IOException {
        // Aliasing for correct naming in stacktrace
        return toFile0(path, opts, attrs);
    }
    
    private long toFile0(
            Path path, Set<? extends OpenOption> opts,
            FileAttribute<?>... attrs) throws IOException {
        requireNonNull(path);
        final var opt = !opts.isEmpty() ? opts : Set.of(WRITE, CREATE_NEW);
        requireNonNull(attrs);
        requireVirtualThread();
        if (isEmpty()) {
            return 0;
        }
        long c = 0;
        try (var dst = FileChannel.open(path, opt, attrs);
             var src = iterator())
        {
            while (src.hasNext()) {
                // On different lines for traceability
                var buf = src.next();
                int r = dst.write(buf);
                assert r > 0;
                c = addExactOrCap(c, r);
            }
        } finally {
            if (opts.contains(CREATE_NEW) && c == 0) {
                Files.deleteIfExists(path);
            }
        }
        return c;
    }
    
    @Override
    public long length() {
        return content.length();
    }
    
    @Override
    public byte[] bytes() throws IOException {
        final long v = length();
        if (v == 0) {
            return EMPTY_BYTEARRAY;
        } else if (v > 0) {
            if (v > Integer.MAX_VALUE) {
                throw new BufferOverflowException();
            }
            return bytesFast((int) v);
        } else {
            assert v == -1;
            return bytesSlow();
        }
    }
    
    private byte[] bytesFast(int cap) throws IOException {
        if (cap > httpServer().getConfig().maxRequestBodyConversionSize()) {
            throw new MaxRequestBodyConversionSizeExceededException();
        }
        final byte[] dst = new byte[cap];
        int offset = 0;
        var it = iterator();
        while (it.hasNext()) {
            final var src = it.next();
            final int rem = src.remaining();
            try {
                src.get(dst, offset, rem);
            } catch (IndexOutOfBoundsException e) {
                // Content length supposed to be reliable at this point lol
                throw new AssertionError("Sink-buffer overflow", e);
            }
            offset += rem;
        }
        assert offset == cap : "Sink-buffer underflow";
        return dst;
    }
    
    private byte[] bytesSlow() throws IOException {
        var os = new ByteArrayOutputStream();
        var it = iterator();
        while (it.hasNext()) {
            var buf = it.next();
            if (!buf.hasRemaining()) {
                assert !it.hasNext() : "End-Of-Stream";
                break;
            }
            if (buf.hasArray()) {
                // Transfer
                os.write(buf.array(), buf.arrayOffset(), buf.remaining());
                // Mark consumed
                buf.position(buf.limit());
            } else {
                while (buf.hasRemaining()) {
                    os.write(buf.get());
                }
            }
        }
        return os.toByteArray();
    }
    
    private class FirstRespond100Continue implements ByteBufferIterator {
        private final ByteBufferIterator d;
        
        FirstRespond100Continue(ByteBufferIterator delegate) {
            d = delegate;
        }
        
        @Override
        public boolean hasNext() {
            return d.hasNext();
        }
        
        @Override
        public ByteBuffer next() throws IOException {
            if (onConsumption != null) {
                try {
                    onConsumption.run();
                } finally {
                    onConsumption = null;
                }
            }
            return d.next();
        }
        
        @Override
        public void forEachRemaining(
                Throwing.Consumer<? super ByteBuffer, ? extends IOException> a)
                throws IOException {
            d.forEachRemaining(a);
        }
        
        @Override
        public void close() throws IOException {
            d.close();
        }
    }
}