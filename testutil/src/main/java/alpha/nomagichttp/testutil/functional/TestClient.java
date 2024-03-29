package alpha.nomagichttp.testutil.functional;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.message.Char;
import alpha.nomagichttp.testutil.IORunnable;
import alpha.nomagichttp.testutil.IOSupplier;
import alpha.nomagichttp.testutil.Interrupt;
import alpha.nomagichttp.util.ExposedByteArrayOutputStream;

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

import static alpha.nomagichttp.testutil.Interrupt.interruptAfter;
import static alpha.nomagichttp.testutil.TestConstants.CRLF;
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
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A utility API on top of a {@code SocketChannel}.<p>
 * 
 * This client gives tests direct control over what bytes are put on the wire
 * and a convenient way of observing what bytes are received. The client has no
 * knowledge about the HTTP protocol. The test is free to implement the protocol
 * however it sees fit, which is sort of the purpose.<p>
 * 
 * Being protocol agnostic means that the client does not implement message
 * framing. Read methods need a given <i>terminator</i> (end of message), after
 * which, the client stops reading.
 * 
 * <pre>
 *  TestClient client = new TestClient(serverPort);
 *  String response = client.writeReadTextUntil(
 *      "GET /greeting HTTP/1.1\r\n\r\n",
 *          "Hi!");
 *  assertThat(response).isEqualTo("""
 *      HTTP/1.1 200 OK\r
 *      Content-Length: 3\r
 *      Content-Type: text/plain; charset=utf-8\r
 *      \r
 *      Hi!""");
 * </pre>
 * 
 * There are methods that has a built-in terminator of two pairs of CR + LF,
 * i.e. they stop reading after the end of the headers. Very handy-dandy when
 * not expecting a response body.
 * 
 * <pre>
 *   String response = client.writeReadTextUntilNewlines("GET /empty HTTP/1.1\r\n\r\n");
 *   assertThat(response).isEqualTo("HTTP/1.1 204 No Content\r\n\r\n");
 * </pre>
 * 
 * Each server-communicating method declared in this class will start and stop
 * a connection used for that call only, unless, a so-called persistent
 * connection has already been opened programmatically using
 * {@link #openConnection()}.<p>
 * 
 * When the connection closes and if unconsumed bytes remain in the read buffer,
 * then an {@code AssertionError} is thrown. This is also true for a persistent
 * connection. In the previous example, had the server sent response body bytes
 * the test would've failed.<p>
 * 
 * Reading will stop at end-of-stream (EOS) even if the terminator wasn't
 * observed. I.e. the terminator is not a required sequence of bytes. The test
 * should always verify that the whole response is exactly what was expected.<p>
 * 
 * To read all available data until EOS, the terminator can either be a sequence
 * of bytes never expected or {@code null}. More conveniently, use a method with
 * a name ending in "EOS". Specifying an empty terminator (empty String or
 * 0-length byte array) will effectively make the read operation NOP.
 * 
 * <pre>
 *   // Server does not support HTTP/1.0 Keep-Alive and will close the connection
 *   String response = client.writeReadTextUntilEOS("GET /empty HTTP/1.0\r\n\r\n");
 * </pre>
 * 
 * Each read/write operation will by default timeout after 2 seconds, giving the
 * HTTP exchange a total of 4 seconds to complete. On timeout, the operation
 * will fail with a {@link ClosedByInterruptException}. Test cases that need
 * more time can override the default using {@link
 * #interruptWriteAfter(long, TimeUnit)} and {@link
 * #interruptReadAfter(long, TimeUnit)} respectively.<p>
 * 
 * This is a complete example of a client syncing his body upload with the
 * server:
 * 
 * <pre>
 *   server.add("/echo-body",
 *       POST().apply(req ->
 *           req.body().toText()));
 *   try (Channel ch = client.openConnection()) {
 *       client.write("""
 *           POST /echo-body HTTP/1.1\r
 *           Expect: 100-continue\r
 *           Content-Length: 3\r
 *           Connection: close\r
 *           \r\n""");
 *       String response1 = client.readTextUntilNewlines();
 *       assertThat(response1).isEqualTo(
 *           "HTTP/1.1 100 Continue\r\n\r\n");
 *       String response2 = client
 *           .write("Hi!")
 *           .shutdownOutput()
 *           .readTextUntilEOS();
 *       assertThat(response2).isEqualTo("""
 *           HTTP/1.1 200 OK\r
 *           Content-Length: 3\r
 *           Content-Type: text/plain; charset=utf-8\r
 *           Connection: close\r
 *           \r
 *           Hi!""");
 *   }
 * </pre>
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
    private static final int BUF_SIZE = 128;
    
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
    
    private static final System.Logger LOG = System.getLogger(TestClient.class.getPackageName());
    
    private final SocketChannelSupplier factory;
    private SocketChannel ch;
    private final ByteBuffer unconsumed;
    private int initialSize;
    
    /**
     * Constructs a {@code TestClient} using a {@code SocketChannel} opened on
     * the port of the given server.
     * 
     * @param server client should connect to
     * @throws IOException if an I/O error occurs
     */
    public TestClient(HttpServer server) throws IOException {
        this(server.getPort());
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
        this.unconsumed = allocate(BUF_SIZE);
        this.initialSize = BUF_SIZE;
        interruptTimeoutReset();
    }
    
    private long rAmount, wAmount;
    private TimeUnit rUnit, wUnit;
    
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
     * Change the read operation's timeout given a delta.<p>
     * 
     * For example, invoking {@code interruptReadAfter(1.5)} on a client that
     * uses default timeout values will increase the timeout from 2 seconds to
     * 3.
     * 
     * @param delta amount
     * @return this for chaining/fluency
     * @see TestClient
     * @see Interrupt
     */
    public TestClient interruptReadAfter(double delta) {
        rAmount = (long) (rUnit.toNanos(rAmount) * delta);
        rUnit = NANOSECONDS;
        return this;
    }
    
    /**
     * Interrupt the write operation after a given timeout.
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
     * Reset the timeout for write and read operations to their default values.
     * 
     * @return this for chaining/fluency
     * @see TestClient
     * @see Interrupt
     */
    public TestClient interruptTimeoutReset() {
        rAmount = 2_000;
        wAmount = 2_000;
        rUnit   = MILLISECONDS;
        wUnit   = MILLISECONDS;
        return this;
    }
    
    /**
     * Opens a persistent connection.<p>
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
     * @return this for chaining/fluency
     */
    public TestClient shutdownOutput() throws IOException {
        requireConnectionIsOpen();
        LOG.log(DEBUG, "Shutting down the output stream.");
        ch.shutdownOutput();
        return this;
    }
    
    /**
     * Shutdown the channel's input stream.
     * 
     * @throws IllegalStateException if a connection is not open
     * @throws IOException if an I/O error occurs
     * @return this for chaining/fluency
     */
    public TestClient shutdownInput() throws IOException {
        requireConnectionIsOpen();
        LOG.log(DEBUG, "Shutting down the input stream.");
        ch.shutdownInput();
        return this;
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
            int r = interruptAfter(1, SECONDS, "serverClosedOutput",
                        () -> ch.read(buf));
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
            interruptAfter(1, SECONDS, "serverClosedInput", () -> {
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
     * Set the initial size of the response buffer.<p>
     * 
     * All response bytes are collected into a {@code byte[]} that grows on
     * demand. To reduce GC pressure, it's advisable to increase the size when
     * expecting a large response.
     * 
     * @param size new initial size
     * @return this for chaining/fluency
     */
    public TestClient responseBufferInitialSize(int size) {
        initialSize = size;
        return this;
    }
    
    /**
     * Write bytes.
     * 
     * @param data to write
     * 
     * @throws NullPointerException if {@code data} is {@code null}
     * @throws IllegalArgumentException if {@code data} is empty
     * @throws IOException if an I/O error occurs
     * @return this for chaining/fluency
     */
    public TestClient write(byte[] data) throws IOException {
        requireContent(data);
        usingConnection(() -> doWrite(data));
        return this;
    }
    
    /**
     * Write text.
     * 
     * @param data to write
     * 
     * @throws NullPointerException if {@code data} is {@code null}
     * @throws IllegalArgumentException if {@code data} is empty
     * @throws IOException if an I/O error occurs
     * @return this for chaining/fluency
     */
    public TestClient write(String data) throws IOException {
        return write(data.getBytes(US_ASCII));
    }
    
    /**
     * Read bytes.
     * 
     * @param terminator end-of-message
     * @return bytes read
     * @throws IOException if an I/O error occurs
     */
    public ByteBuffer readBytesUntil(byte[] terminator) throws IOException {
        final var sink = new FiniteByteBufferSink(initialSize, terminator);
        // Drain unconsumed
        if (unconsumed.flip().hasRemaining()) {
            sink.write(unconsumed);
            if (unconsumed.hasRemaining()) {
                assert sink.hasReachedEnd();
                ByteBuffer residue = ByteBuffer.wrap(
                        unconsumed.array(),
                        unconsumed.position(),
                        unconsumed.remaining());
                unconsumed.clear();
                unconsumed.put(residue);
                return sink.toByteBuffer();
            }
        }
        unconsumed.clear();
        if (sink.hasReachedEnd()) {
            return sink.toByteBuffer();
        }
        // Thirsty for more, read from channel
        try {
            usingConnection(() -> doRead(sink));
        } catch (IOException e) {
            sink.dumpToLog();
            throw e;
        }
        return sink.toByteBuffer();
    }
    
    /**
     * Read bytes until {@code CRLF + CRLF} (end of HTTP headers).
     * 
     * @return bytes read
     * @throws IOException if an I/O error occurs
     */
    public ByteBuffer readBytesUntilNewlines() throws IOException {
        return readBytesUntil((CRLF + CRLF).getBytes(US_ASCII));
    }
    
    /**
     * Read bytes until end-of-stream.
     * 
     * @return bytes read
     * @throws IOException if an I/O error occurs
     */
    public ByteBuffer readBytesUntilEOS() throws IOException {
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
        ByteBuffer bytes = readBytesUntil(
                terminator == null ? null : terminator.getBytes(US_ASCII));
        return new String(bytes.array(), bytes.arrayOffset(), bytes.remaining(), US_ASCII);
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
    public ByteBuffer writeReadBytesUntil(byte[] data, byte[] terminator) throws IOException {
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
    public ByteBuffer writeReadBytesUntilEOS(byte[] data) throws IOException {
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
        ByteBuffer bytes = writeReadBytesUntil(
                data.getBytes(US_ASCII),
                terminator == null ? null : terminator.getBytes(US_ASCII));
        return new String(bytes.array(), 0, bytes.remaining(), US_ASCII);
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
        interruptAfter(wAmount, wUnit, "doWrite", () -> {
            int r = ch.write(wrap(data));
            assertThat(r).isEqualTo(data.length);
        });
    }
    
    private void doRead(FiniteByteBufferSink sink) throws IOException {
        interruptAfter(rAmount, rUnit, "doRead", () -> {
            ByteBuffer buf = allocate(BUF_SIZE);
            while (!sink.hasReachedEnd()) {
                if (ch.read(buf) == -1) {
                    LOG.log(DEBUG, "EOS; server closed my read stream.");
                    break;
                }
                buf.flip();
                sink.write(buf);
                if (buf.hasRemaining()) {
                    assert sink.hasReachedEnd();
                    unconsumed.put(buf);
                }
                buf.clear();
            }
        });
    }
    
    private void usingConnection(IORunnable op) throws IOException {
        IOSupplier<ByteBuffer> wrapper = () -> {
            op.run();
            return null;
        };
        usingConnection(wrapper);
    }
    
    private ByteBuffer usingConnection(IOSupplier<ByteBuffer> op) throws IOException {
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
            LOG.log(DEBUG, "Closing the test client's channel.");
            ch.close();
            ch = null;
        }
        if (unconsumed.flip().hasRemaining()) {
            throw new AssertionError(
                "Unconsumed bytes in buffer: " + FiniteByteBufferSink.dump(unconsumed));
        }
    }
    
    private static class FiniteByteBufferSink {
        private final ExposedByteArrayOutputStream delegate;
        private final byte[] terminator;
        private int matched;
        
        FiniteByteBufferSink(int initialSize, byte[] terminator) {
            this.delegate   = new ExposedByteArrayOutputStream(initialSize);
            this.terminator = terminator;
            this.matched    = 0;
        }
        
        /**
         * Consumes the data, up until and including the terminator, which is
         * not necessary all of the given bytebuffer.
         * 
         * @param data to consume
         */
        void write(ByteBuffer data) {
            while (!hasReachedEnd() && data.hasRemaining()) {
                byte b = data.get();
                delegate.write(b);
                memorize(b);
            }
        }
        
        private void memorize(byte b) {
            if (terminator == null || terminator.length == 0) {
                return;
            }
            // <matched> is both an index (which byte need to be matched next)
            // and a count on how many bytes have been matched so far.
            if (b == terminator[matched]) {
                // Next input byte just observed at correct terminator index, bump!
                ++matched;
                // In all other cases, there's no match with current index.
                // But we can't just undo previous matches and set matched = 0.
                // Ex.
                //   Input = "abcdEEOM"   <-- 'E' is repeated
                //   Terminator = "EOM"
                //   First 'E' is a match. Matched = 1 and next index to check = 1.
                //   Second 'E' != terminator[matched]/'O',
                //   but it was a match against previous index.
                //   I.e. no rollback and matched = 1 remains valid.
            } else while (matched > 0 && b != terminator[matched - 1]) {
                --matched;
            }
        }
        
        boolean hasReachedEnd() {
            if (terminator == null) {
                return false;
            }
            return matched == terminator.length;
        }
        
        ByteBuffer toByteBuffer() {
            return ByteBuffer.wrap(delegate.buffer(), 0, delegate.count());
        }
        
        void dumpToLog() {
            if (!LOG.isLoggable(WARNING)) {
                return;
            }
            Collection<String> chars = dump(delegate.buffer(), 0, delegate.count());
            LOG.log(WARNING, "About to crash. Will log start+end of what we received thus far.");
            chars.forEach(c -> LOG.log(WARNING, c));
        }
        
        private static Collection<String> dump(ByteBuffer bytes) {
            int start = bytes.arrayOffset() + bytes.position(),
                end   = start + bytes.remaining();
            return dump(bytes.array(), start, end);
        }
        
        private static Collection<String> dump(byte[] bytes, int start, int end) {
            // Max number of chars in beginning and end
            final int max = 5;
            final String prefix = "\n    ";
            
            List<String> l = new ArrayList<>();
            
            // TODO: Ultra simplified iteration of all indices.
            //       Mathematically just SKIP the middle section instead of iterating through it.
            int skipped = 0;
            for (int i = start; i < end; ++i) {
                if (i > max - 1 && i < end - max) {
                    ++skipped;
                    continue;
                } else if (skipped > 0) {
                    l.add(prefix + "<...skipped " + skipped + " byte(s)...>");
                    skipped = -1;
                }
                l.add(prefix + Char.toDebugString((char) bytes[i]));
            }
            
            return l;
        }
    }
}