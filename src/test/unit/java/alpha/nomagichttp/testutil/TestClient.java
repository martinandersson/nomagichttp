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
 * This class provides low-level access for test cases that need direct control
 * over what bytes are put on the wire and monitor what is received. This class
 * has no knowledge about the HTTP protocol.<p>
 * 
 * All write methods will by default open/close a new connection for each call.
 * In order to re-use a persistent connection across method calls, manually
 * {@link #openConnection()} and close the returned channel after the last
 * message exchange.<p>
 *  
 * When to stop reading from the channel has to be specified by proving the last
 * bytes expected in the server's response. This will trigger the test client to
 * assert there's no more bytes left in the channel and return all bytes
 * received.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class TestClient
{
    /**
     * Client/delegate factory.
     */
    @FunctionalInterface
    public interface SocketChannelSupplier {
        /**
         * Creates the client delegate.
         * 
         * @return the client delegate
         * 
         * @throws IOException if an I/O error occurs
         */
        SocketChannel get() throws IOException;
    }
    
    /**
     * An HTTP newline.
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
    
    /**
     * Open a connection.<p>
     * 
     * Test code must manually close the returned channel.
     * 
     * @return the channel
     * 
     * @throws IllegalStateException if a connection is already active
     * @throws IOException like for other weird stuff
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
    
    private void closeChannel() throws IOException {
        if (ch != null) {
            ch.close();
            LOG.log(DEBUG, "Closed client channel.");
            ch = null;
        }
    }
    
    /**
     * Decode and subsequently write the bytes on the connection using {@code
     * US_ASCII}.<p>
     * 
     * Please note that UTF-8 is backwards compatible with ASCII.
     * 
     * @param text to write
     * 
     * @throws ClosedByInterruptException
     *             if operation takes longer than 3 seconds
     * 
     * @throws IOException
     *             for other weird reasons lol
     */
    public void write(String text) throws IOException {
        writeRead(text, "");
    }
    
    /**
     * Same as {@link #writeRead(String, String)} but with a response end
     * hardcoded to be "\r\n\r\n".<p>
     * 
     * Useful when <i>not</i> expecting a response body, in which case the
     * response should end after the headers with two newlines.
     * 
     * @param request to write
     * 
     * @return the response
     * 
     * @throws ClosedByInterruptException
     *             if operation takes longer than 3 seconds
     * 
     * @throws IOException
     *             for other weird reasons lol
     */
    public String writeRead(String request) throws IOException {
        return writeRead(request, CRLF + CRLF);
    }
    
    /**
     * Same as {@link #writeRead(byte[], byte[])} except this method decodes the
     * arguments and encodes the response using {@code US_ASCII}.<p>
     * 
     * Useful when sending ASCII data and expecting an ASCII response.<p>
     * 
     * Please note that UTF-8 is backwards compatible with ASCII.
     * 
     * @param request to write
     * @param responseEnd last expected chunk in response
     * 
     * @return the response
     * 
     * @throws ClosedByInterruptException
     *             if operation takes longer than 3 seconds
     * 
     * @throws IOException
     *             for other weird reasons lol
     */
    public String writeRead(String request, String responseEnd) throws IOException {
        byte[] bytes = writeRead(
                request.getBytes(US_ASCII),
                responseEnd.getBytes(US_ASCII));
        
        return new String(bytes, US_ASCII);
    }
    
    /**
     * Write a bunch of bytes to the server, and receive a bunch of bytes back.<p>
     * 
     * This method will not stop reading the response from the server until the
     * last chunk of bytes specified by {@code responseEnd} has been received.<p>
     * 
     * Please note that if this method throws an {@code InterruptedException}
     * then this could be because the test case expected a different end of the
     * response than what was received.<p>
     * 
     * @param request bytes to write
     * @param responseEnd last chunk of expected bytes in response
     * 
     * @return the response
     * 
     * @throws ClosedByInterruptException
     *             if operation takes longer than 3 seconds
     * 
     * @throws IOException
     *             for other weird reasons lol
     */
    public byte[] writeRead(byte[] request, byte[] responseEnd) throws IOException {
        final FiniteByteBufferSink sink = new FiniteByteBufferSink(128, responseEnd);
        final boolean persistent = ch != null;
        
        try {
            if (!persistent) {
                openConnection();
            }
            
            Interrupt.after(3, SECONDS, () -> {
                int r = ch.write(wrap(request));
                assertThat(r).isEqualTo(request.length);
                
                ByteBuffer buf = allocate(128);
                
                while (!sink.hasReachedEnd()) {
                    if (ch.read(buf) == -1) {
                        LOG.log(DEBUG, "EOS; server closed channel's read stream. Closing our channel.");
                        ch.close();
                        break;
                    }
                    
                    buf.flip();
                    sink.write(buf);
                    buf.clear();
                }
            });
            
            return sink.toByteArray();
        } catch (Exception e) {
            sink.dumpToLog();
            throw e;
        }
        finally {
            if (!persistent) {
                closeChannel();
            }
        }
    }
    
    /**
     * Read until end-of-stream.
     * 
     * @return what's left in the channel's read stream
     * 
     * @throws ClosedByInterruptException if operation takes longer than 3 seconds
     * @throws IOException for other weird reasons lol
     */
    public byte[] drain() throws IOException {
        return writeRead(new byte[0], null);
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
                throw new IllegalStateException();
            }
            
            int start = data.arrayOffset() + data.position(),
                end   = start + data.remaining();
            
            // TODO: Rework this. We can't assert that we read and consume everything from the channel.
            //       We should read our data until we reach end, then not consume the remaining.
            //       AbstractRealTest must @AfterEach assert that at that point all data in channel was consumed.
            
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
        
        private static Collection<String> dump(byte[] bytes, int start, int end) {
            List<String> l = new ArrayList<>();
            
            for (int i = start; i < end; ++i) {
                l.add("\n" + Char.toDebugString((char) bytes[i]));
            }
            
            return l;
        }
    }
}