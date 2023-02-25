package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.BadRequestException;
import alpha.nomagichttp.message.ByteBufferIterable;
import alpha.nomagichttp.message.ByteBufferIterator;
import alpha.nomagichttp.message.ContentHeaders;
import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.UnsupportedTransferCodingException;
import alpha.nomagichttp.util.Throwing;

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

import static alpha.nomagichttp.HttpConstants.HeaderName.CONTENT_LENGTH;
import static alpha.nomagichttp.HttpConstants.HeaderName.TRANSFER_ENCODING;
import static alpha.nomagichttp.internal.Blah.requireVirtualThread;
import static alpha.nomagichttp.util.Blah.EMPTY_BYTEARRAY;
import static alpha.nomagichttp.util.Blah.addExactOrMaxValue;
import static java.nio.ByteBuffer.wrap;
import static java.nio.charset.CodingErrorAction.REPORT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Default implementation of {@code Request.Body}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class RequestBody implements Request.Body
{
    // Copy-pasted from FileChannel.NO_ATTRIBUTES
    private static final FileAttribute<?>[]
            NO_ATTRIBUTES = new FileAttribute[0];
    
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
                    CONTENT_LENGTH + " and " + TRANSFER_ENCODING + " present");
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
    public long toFile(Path file, OpenOption... options) throws IOException {
        return toFile(file, Set.of(options), NO_ATTRIBUTES);
    }
    
    @Override
    public long toFile(
            Path path, Set<? extends OpenOption> opts,
            FileAttribute<?>... attrs)
            throws IOException
    {
        if (isEmpty()) {
            return 0;
        }
        requireVirtualThread();
        final var opt = !opts.isEmpty() ? opts : Set.of(WRITE, CREATE_NEW);
        long c = 0;
        try (var dst = FileChannel.open(path, opt, attrs);
             var src = iterator())
        {
            while (src.hasNext()) {
                // On different lines for traceability
                var buf = src.next();
                int r = dst.write(buf);
                assert r > 0;
                c = addExactOrMaxValue(c, r);
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
        long v = requireKnownLength();
        if (v == 0) {
            return EMPTY_BYTEARRAY;
        }
        if (v > Integer.MAX_VALUE) {
            throw new BufferOverflowException();
        }
        return bytes0((int) v);
    }
    
    private long requireKnownLength() {
        long len = length();
        if (len == -1) {
            throw new UnsupportedOperationException("Length is unknown");
        }
        return len;
    }
    
    private byte[] bytes0(int cap) throws IOException {
        assert cap >= 0;
        final byte[] dst = new byte[cap];
        int offset = 0;
        var it = content.iterator();
        while (it.hasNext()) {
            final var src = it.next();
            final int len = src.remaining();
            try {
                src.get(dst, offset, len);
            } catch (IndexOutOfBoundsException e) {
                // Content length supposed to be reliable at this point lol
                throw new AssertionError("Sink-buffer overflow", e);
            }
            offset += len;
        }
        assert offset == cap : "Sink-buffer underflow";
        return dst;
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