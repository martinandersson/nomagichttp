package alpha.nomagichttp.testutil;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.message.Char;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static alpha.nomagichttp.util.IOExceptions.isCausedByBrokenInputStream;
import static alpha.nomagichttp.util.IOExceptions.isCausedByBrokenOutputStream;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;
import static java.net.InetAddress.getLoopbackAddress;
import static java.net.StandardSocketOptions.SO_RCVBUF;
import static java.net.StandardSocketOptions.SO_SNDBUF;
import static java.nio.ByteBuffer.allocate;
import static java.nio.ByteBuffer.wrap;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A utility API on top of a {@code SocketChannel}.<p>
 * 
 * This class provides low-level access for test cases in direct control over
 * what bytes are put on the wire and to observe what bytes are received. This
 * class has no knowledge about the HTTP protocol. The test must implement the
 * protocol.<p>
 * 
 * Low-level read methods accept a terminator (an expected "response end"). This
 * is a sequence of bytes after which, the client stops reading. If trailing
 * [unexpected] bytes are observed, an {@code AssertionError} is thrown. The
 * read will stop at end-of-stream and not fail the test even if not all of the
 * terminator has been observed. The test should always run asserts on the
 * returned response.<p>
 * 
 * To read all available data until EOS, the terminator can either be a sequence
 * of bytes never expected or {@code null}. More conveniently, use an override
 * with a name ending in "EOS". Specifying an empty terminator (empty String or
 * 0-length byte array) effectively asserts that no bytes were received prior to
 * EOS.<p>
 * 
 * Each read/write operation will by default timeout after 1 second, at which
 * point the operation will fail with a {@link ClosedByInterruptException}. Test
 * cases that need more time can override the default using {@link
 * #interruptWriteAfter(long, TimeUnit)} and {@link
 * #interruptReadAfter(long, TimeUnit)} respectively.<p>
 * 
 * All methods in this class will by default open/close a new connection for
 * each call, unless one is already opened. The write operation does not
 * explicitly close the output stream when it completes. For manual control over
 * the connection, such as closing individual streams, or re-using the
 * same connection across method calls, manually {@link #openConnection()}
 * first.<p>
 * 
 * Strings will be encoded/decoded using {@code US_ASCII}. Please note that
 * UTF-8 is backwards compatible with ASCII.<p>
 * 
 * This class is not thread-safe and all I/O operations block.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class TestClient
{
    /**
     * Factory of the channel to use for all operations.
     */
    @FunctionalInterface
    public interface SocketChannelSupplier {
        /**
         * Creates the channel.
         * 
         * @return the channel
         * 
         * @throws IOException if an I/O error occurs
         */
        SocketChannel get() throws IOException;
    }
    
    /**
     * A HTTP newline.
     */
    public static final String CRLF = "\r\n";
    
    private static final System.Logger LOG = System.getLogger(TestClient.class.getPackageName());
    
    private final SocketChannelSupplier factory;
    private SocketChannel ch;
    
    /**
     * Constructs a {@code TestClient} using a {@code SocketChannel} opened on
     * the port of the given server.
     * 
     * @param server client should connect to
     * @throws IOException if an I/O error occurs
     */
    public TestClient(HttpServer server) throws IOException {
        this(server.getLocalAddress().getPort());
    }
    
    /**
     * Constructs a {@code TestClient} using a {@code SocketChannel} opened on
     * the given port.
     * 
     * @param port of client channel
     */
    public TestClient(int port) {
        this(() -> SocketChannel.open(
                new InetSocketAddress(getLoopbackAddress(), port)));
    }
    
    /**
     * Constructs a {@code TestClient}.
     * 
     * @param factory of client
     */
    public TestClient(SocketChannelSupplier factory) {
        this.factory = requireNonNull(factory);
    }
    
    private long     rAmount = 1,
                     wAmount = 1;
    private TimeUnit rUnit   = SECONDS,
                     wUnit   = SECONDS;
    
    /**
     * Interrupt the read operation after a given timeout.
     * 
     * @param amount of duration
     * @param unit unit of amount
     * @return this for chaining/fluency
     * @see TestClient
     * @see Interrupt
     */
    public TestClient interruptReadAfter(long amount, TimeUnit unit) {
        rAmount = amount;
        rUnit = unit;
        return this;
    }
    
    /**
     * Interrupt the write operation after a given timeout.<p>
     * 
     * @param amount of duration
     * @param unit unit of amount
     * @return this for chaining/fluency
     * @see TestClient
     * @see Interrupt
     */
    public TestClient interruptWriteAfter(long amount, TimeUnit unit) {
        wAmount = amount;
        wUnit = unit;
        return this;
    }
    
    /**
     * Open a connection.<p>
     * 
     * Test code must manually close the returned channel.
     * 
     * @return the channel
     * 
     * @throws IllegalStateException if a connection is already active
     * @throws IOException if an I/O error occurs
     */
    public Channel openConnection() throws IOException {
        if (ch != null) {
            throw new IllegalStateException("Already opened.");
        }
        
        Channel ch = this.ch = factory.get();
        assert this.ch.isBlocking();
        
        class Proxy implements Channel {
            @Override
            public boolean isOpen() {
                return ch.isOpen();
            }
            
            @Override
            public void close() throws IOException {
                closeChannel();
            }
        }
        
        return new Proxy();
    }
    
    /**
     * Shutdown the channel's output stream.
     * 
     * @throws IllegalStateException if a connection is not open
     * @throws IOException if an I/O error occurs
     */
    public void shutdownOutput() throws IOException {
        requireConnectionIsOpen();
        ch.shutdownOutput();
        LOG.log(DEBUG, "Shut down channel's output stream.");
    }
    
    /**
     * Shutdown the channel's input stream.
     * 
     * @throws IllegalStateException if a connection is not open
     * @throws IOException if an I/O error occurs
     */
    public void shutdownInput() throws IOException {
        requireConnectionIsOpen();
        ch.shutdownInput();
        LOG.log(DEBUG, "Shut down channel's input stream.");
    }
    
    /**
     * Will return {@code true} if it can be assumed that the other peer closed
     * his output stream (analogous to our input stream and reading ability).<p>
     * 
     * An attempt will be made to manipulate the channel's receiving buffer size
     * and read bytes from the channel. Hence, the test must only call this
     * method at the very end of the channel's life. Otherwise the test will
     * risk running into a message protocol error later.
     * 
     * @return {@code true} if other peer closed his output stream,
     *         otherwise {@code false}
     * 
     * @throws IllegalStateException if no connection is active,
     *                               or we closed our channel
     */
    public boolean serverClosedOutput() {
        requireConnectionIsOpen();
        int size = setSmallBufferGetActual(SO_RCVBUF);
        ByteBuffer buf = allocate(size + 1);
        try {
            int r = Interrupt.after(1, SECONDS, () -> ch.read(buf));
            return r == -1;
        } catch (IOException e) {
            boolean broken = isCausedByBrokenInputStream(e);
            if (!broken) {
                LOG.log(DEBUG, "Exception not considered broken read.", e);
            }
            return broken;
        }
    }
    
    /**
     * Will return {@code true} if it can be assumed that the other peer closed
     * his input stream (analogous to our output stream and writing ability).<p>
     * 
     * An attempt will be made to manipulate the channel's send buffer size and
     * write bytes to the channel. Hence, the test must only call this method at
     * the very end of the channel's life. Otherwise the test will risk running
     * into a message protocol error later.<p>
     * 
     * If test is probing the status of both of the server's streams, then test
     * should call {@link #serverClosedOutput()} first, followed by this method.
     * Reason is that the attempted read is more prone to pick up the FIN packet
     * (EOS) whereas writing significant amounts of data to a closed connection
     * may actually succeed without hitting a broken pipe error.
     * 
     * @return {@code true} if other peer closed his input stream,
     *         otherwise {@code false}
     *
     * @throws IllegalStateException if no connection is active,
     *                               or we closed our channel
     */
    public boolean serverClosedInput() {
        requireConnectionIsOpen();
        int size = setSmallBufferGetActual(SO_SNDBUF);
        ByteBuffer buf = allocate(size + 1);
        try {
            // Test 1
            Interrupt.after(1, SECONDS, () -> {
                while (buf.hasRemaining()) {
                    int r = ch.write(buf);
                    assertThat(r).isPositive().describedAs(
                            "Blocking mode; expecting IOException, not 0");
                }
            });
            
            // Test 2
            ch.socket().sendUrgentData(1);
            
            // Windows 10 will detect broken pipe using either Test 1, 2 or both
            // Ubuntu 20.04 requires both Test 1 and 2
            
            return false;
        } catch (IOException e) {
            boolean broken = isCausedByBrokenOutputStream(e);
            if (!broken) {
                LOG.log(DEBUG, "Exception not considered broken write.", e);
            }
            return broken;
        }
    }
    
    private void requireConnectionIsOpen() {
        if (ch == null) {
            throw new IllegalStateException("No connection active.");
        }
        if (!ch.isOpen()) {
            throw new IllegalStateException("Channel closed on our side.");
        }
    }
    
    private int setSmallBufferGetActual(SocketOption<Integer> sendOrReceive) {
        // TODO: To future proof this, we should accept default on "Invalid arg" crash
        try {
            // Windows 10 and Ubuntu 20.04 accepts a 0 as argument.
            // Although it has no effect on Ubuntu.
            // MacOS won't accept 0 as argument; java.net.SocketException: Invalid argument
            ch.setOption(sendOrReceive, 1);
            return ch.getOption(sendOrReceive);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to set/get socket option.", e);
        }
    }
    
    /**
     * Write bytes.
     * 
     * @param data to write
     * 
     * @throws NullPointerException if {@code data} is {@code null}
     * @throws IllegalArgumentException if {@code data} is empty
     * @throws IOException if an I/O error occurs
     */
    public void write(byte[] data) throws IOException {
        requireContent(data);
        usingConnection(() -> doWrite(data));
    }
    
    /**
     * Write text.
     * 
     * @param data to write
     * 
     * @throws NullPointerException if {@code data} is {@code null}
     * @throws IllegalArgumentException if {@code data} is empty
     * @throws IOException if an I/O error occurs
     */
    public void write(String data) throws IOException {
        write(data.getBytes(US_ASCII));
    }
    
    /**
     * Read bytes.
     * 
     * @param terminator end-of-message
     * @return bytes read
     * @throws IOException if an I/O error occurs
     */
    public byte[] readBytesUntil(byte[] terminator) throws IOException {
        final FiniteByteBufferSink sink = new FiniteByteBufferSink(128, terminator);
        try {
            usingConnection(() -> doRead(sink));
        } catch (IOException e) {
            sink.dumpToLog();
            throw e;
        }
        return sink.toByteArray();
    }
    
    /**
     * Read bytes until {@code CRLF + CRLF} (end of HTTP headers).
     * 
     * @return bytes read
     * @throws IOException if an I/O error occurs
     */
    public byte[] readBytesUntilNewlines() throws IOException {
        return readBytesUntil((CRLF + CRLF).getBytes(US_ASCII));
    }
    
    /**
     * Read bytes until end-of-stream.
     * 
     * @return bytes read
     * @throws IOException if an I/O error occurs
     */
    public byte[] readBytesUntilEOS() throws IOException {
        return readBytesUntil(null);
    }
    
    /**
     * Read text.
     * 
     * @return text read
     * @param terminator end-of-message
     * @throws IOException if an I/O error occurs
     */
    public String readTextUntil(String terminator) throws IOException {
        byte[] rsp = readBytesUntil(
                terminator == null ? null : terminator.getBytes(US_ASCII));
        return new String(rsp, US_ASCII);
    }
    
    /**
     * Read text until {@code CRLF + CRLF} (end of HTTP headers).
     * 
     * @return text read
     * @throws IOException if an I/O error occurs
     */
    public String readTextUntilNewlines() throws IOException {
        return readTextUntil(CRLF + CRLF);
    }
    
    /**
     * Read text until end-of-stream.
     * 
     * @return text read
     * @throws IOException if an I/O error occurs
     */
    public String readTextUntilEOS() throws IOException {
        return readTextUntil(null);
    }
    
    /**
     * Write + read bytes.
     * 
     * @param data to write
     * @param terminator end-of-message
     * 
     * @return bytes read
     * 
     * @throws NullPointerException if {@code data} is {@code null}
     * @throws IllegalArgumentException if {@code data} is empty
     * @throws IOException if an I/O error occurs
     */
    public byte[] writeReadBytesUntil(byte[] data, byte[] terminator) throws IOException {
        requireContent(data);
        return usingConnection(() -> {
            write(data);
            return readBytesUntil(terminator);
        });
    }
    
    /**
     * Write + read bytes until end-of-stream.
     * 
     * @param data to write
     * 
     * @return bytes read
     * 
     * @throws NullPointerException if {@code data} is {@code null}
     * @throws IllegalArgumentException if {@code data} is empty
     * @throws IOException if an I/O error occurs
     */
    public byte[] writeReadBytesUntilEOS(byte[] data) throws IOException {
        return writeReadBytesUntil(data, null);
    }
    
    /**
     * Write + read text.<p>
     * 
     * This method is the most high-level method that can be used to write
     * a request and get a response with a body in return. The end of the body
     * ought to be the terminator specified.<p>
     * 
     * Or, consider using {@link #writeReadTextUntilEOS(String)} if it is
     * expected that the server will close the client's read stream after the
     * response.
     * 
     * @param data to write
     * @param terminator end-of-message
     * 
     * @return text read
     * 
     * @throws NullPointerException if {@code data} is {@code null}
     * @throws IllegalArgumentException if {@code data} is empty
     * @throws IOException if an I/O error occurs
     */
    public String writeReadTextUntil(String data, String terminator) throws IOException {
        byte[] rsp = writeReadBytesUntil(
                data.getBytes(US_ASCII),
                terminator == null ? null : terminator.getBytes(US_ASCII));
        
        return new String(rsp, US_ASCII);
    }
    
    /**
     * Write + read text until {@code CRLF + CRLF} (end of HTTP headers).<p>
     * 
     * This method is the most high-level method that can be used to write
     * a request and get a response without a body in return.<p>
     * 
     * Or, consider using {@link #writeReadTextUntilEOS(String)} if it is
     * expected that the server will close the client's read stream after the
     * response.
     * 
     * @param data to write
     * 
     * @return text read
     * 
     * @throws NullPointerException if {@code data} is {@code null}
     * @throws IllegalArgumentException if {@code data} is empty
     * @throws IOException if an I/O error occurs
     */
    public String writeReadTextUntilNewlines(String data) throws IOException {
        return writeReadTextUntil(data, CRLF + CRLF);
    }
    
    /**
     * Write + read text until end-of-stream.<p>
     * 
     * This method is the most high-level method that can be used to write
     * a request and get a response in return where it is expected that the
     * server also closes the client's read stream afterwards.
     * 
     * @param data to write
     * 
     * @return text read
     * 
     * @throws NullPointerException if {@code data} is {@code null}
     * @throws IllegalArgumentException if {@code data} is empty
     * @throws IOException if an I/O error occurs
     */
    public String writeReadTextUntilEOS(String data) throws IOException {
        return writeReadTextUntil(data, null);
    }
    
    private void doWrite(byte[] data) throws IOException {
        requireContent(data);
        Interrupt.after(wAmount, wUnit, () -> {
            int r = ch.write(wrap(data));
            assertThat(r).isEqualTo(data.length);
        });
    }
    
    private void doRead(FiniteByteBufferSink sink) throws IOException {
        Interrupt.after(rAmount, rUnit, () -> {
            ByteBuffer buf = allocate(128);
            while (!sink.hasReachedEnd()) {
                if (ch.read(buf) == -1) {
                    LOG.log(DEBUG, "EOS; server closed channel's read stream.");
                    break;
                }
                buf.flip();
                sink.write(buf);
                buf.clear();
            }
        });
    }
    
    private void usingConnection(IORunnable op) throws IOException {
        IOSupplier<byte[]> wrapper = () -> {
            op.run();
            return null;
        };
        usingConnection(wrapper);
    }
    
    private byte[] usingConnection(IOSupplier<byte[]> op) throws IOException {
        final boolean persistent = ch != null;
        try {
            if (!persistent) {
                openConnection();
            }
            return op.get();
        } finally {
            if (!persistent) {
                closeChannel();
            }
        }
    }
    
    private void requireContent(byte[] bytes) {
        if (bytes.length == 0) {
            throw new IllegalArgumentException("No data specified.");
        }
    }
    
    private void closeChannel() throws IOException {
        if (ch != null) {
            ch.close();
            LOG.log(DEBUG, "Closed client channel.");
            ch = null;
        }
    }
    
    private static class FiniteByteBufferSink {
        private final ByteArrayOutputStream delegate;
        private final byte[] terminator;
        private int matched;
        
        FiniteByteBufferSink(int initialSize, byte[] terminator) {
            this.delegate = new ByteArrayOutputStream(initialSize);
            this.terminator = terminator;
            matched = 0;
        }
        
        void write(ByteBuffer data) {
            if (hasReachedEnd()) {
                assert data.hasRemaining();
                throw new AssertionError(
                    "Unexpected trailing bytes in response: " + dump(data));
            }
            
            int start = data.arrayOffset() + data.position(),
                end   = start + data.remaining();
            
            for (int i = start; i < end; ++i) {
                byte b = data.array()[i];
                delegate.write(b);
                memorize(b);
                
                if (hasReachedEnd()) {
                    assertThat(i + 1 == end)
                            .as("Unexpected trailing bytes in response: " + dump(data.array(), i + 1, end))
                            .isTrue();
                }
            }
        }
        
        private void memorize(byte b) {
            if (terminator == null) {
                return;
            }
            
            if (b == terminator[matched]) {
                ++matched;
            } else {
                matched = 0;
            }
        }
        
        boolean hasReachedEnd() {
            if (terminator == null) {
                return false;
            }
            
            return matched == terminator.length;
        }
        
        byte[] toByteArray() {
            return delegate.toByteArray();
        }
        
        void dumpToLog() {
            if (!LOG.isLoggable(WARNING)) {
                return;
            }
            
            byte[] b = delegate.toByteArray();
            Collection<String> chars = dump(b, 0, b.length);
            LOG.log(WARNING, "About to crash. We received this many bytes: " + chars.size() + ". Will log each as a char.");
            dump(b, 0, b.length).forEach(c -> LOG.log(WARNING, c));
        }
        
        private static Collection<String> dump(ByteBuffer bytes) {
            int start = bytes.arrayOffset() + bytes.position(),
                end   = start + bytes.remaining();
            return dump(bytes.array(), start, end);
        }
        
        private static Collection<String> dump(byte[] bytes, int start, int end) {
            List<String> l = new ArrayList<>();
            
            for (int i = start; i < end; ++i) {
                l.add("\n" + Char.toDebugString((char) bytes[i]));
            }
            
            return l;
        }
    }
}