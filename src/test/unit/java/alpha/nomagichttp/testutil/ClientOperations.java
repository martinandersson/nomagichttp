package alpha.nomagichttp.testutil;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.message.Char;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;
import static java.net.InetAddress.getLoopbackAddress;
import static java.nio.ByteBuffer.allocate;
import static java.nio.ByteBuffer.wrap;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A utility API on top of a {@code SocketChannel}.<p>
 * 
 * Any operation taking 3 seconds or longer will cause an {@code
 * InterruptedException} to be thrown<p>
 * 
 * All channel operating utility methods will by default open/close a new
 * connection for each method call into this class. In order to re-use a
 * persistent connection across method calls, first invoke {@link
 * #openConnection()} .<p>
 * 
 * Note: This class provides low-level access for test cases that need direct
 * control over what bytes are put on the wire and monitor what is received.
 * Test cases that operate on a higher "HTTP exchange semantics kind of layer"
 * should use a real client such as JDK's {@link HttpClient} instead.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class ClientOperations
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
    
    private static final System.Logger LOG = System.getLogger(ClientOperations.class.getPackageName());
    
    private static final ScheduledExecutorService SCHEDULER
            = Executors.newSingleThreadScheduledExecutor(r -> {
                  Thread t = new Thread(r);
                  t.setDaemon(true);
                  return t;
            });
    
    private final SocketChannelSupplier factory;
    private SocketChannel ch;
    
    /**
     * Constructs a {@code ClientOperations} using a {@code SocketChannel}
     * opened on the port of the given server.
     * 
     * @param server client should connect to
     */
    public ClientOperations(HttpServer server) {
        this(server.getLocalAddress().getPort());
    }
    
    /**
     * Constructs a {@code ClientOperations} using a {@code SocketChannel}
     * opened on the given port.
     * 
     * @param port of client channel
     */
    public ClientOperations(int port) {
        this(() -> SocketChannel.open(
                new InetSocketAddress(getLoopbackAddress(), port)));
    }
    
    /**
     * Constructs a {@code ClientOperations}.
     * 
     * @param factory of client
     */
    public ClientOperations(SocketChannelSupplier factory) {
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
                ClientOperations.this.ch = null;
                ch.close();
            }
        }
        
        return new Proxy();
    }
    
    private void closeConnection() throws IOException {
        if (ch != null) {
            ch.close();
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
     * @throws IOException if an I/O error occurs
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
     * @return the response
     * @throws IOException if an I/O error occurs
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
     * @throws IOException if an I/O error occurs
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
     * @throws ClosedByInterruptException if operation takes longer than 3 seconds
     * @throws IOException for other weird reasons lol
     */
    public byte[] writeRead(byte[] request, byte[] responseEnd) throws IOException {
        final Thread worker = Thread.currentThread();
        final AtomicBoolean communicating = new AtomicBoolean(true);
        
        ScheduledFuture<?> interrupt = SCHEDULER.schedule(() -> {
            if (communicating.get()) {
                LOG.log(WARNING, "HTTP exchange took too long, will timeout.");
                worker.interrupt();
            }
        }, 3, SECONDS);
        
        final FiniteByteBufferSink sink = new FiniteByteBufferSink(128, responseEnd);
        final boolean persistent = ch != null;
        
        try {
            if (!persistent) {
                openConnection();
            }
            
            int r = ch.write(wrap(request));
            assertThat(r).isEqualTo(request.length);
            
            ByteBuffer buff = allocate(128);
            
            while (!sink.hasReachedEnd()) {
                if (ch.read(buff) == -1) {
                    LOG.log(DEBUG, "EOS; server closed channel's read stream. Closing our channel.");
                    ch.close();
                    break;
                }
                
                buff.flip();
                sink.write(buff);
                buff.clear();
            }
            
            return sink.toByteArray();
        } catch (Exception e) {
            sink.dumpToLog();
            throw e;
        }
        finally {
            communicating.set(false);
            interrupt.cancel(false);
            Thread.interrupted(); // clear flag
            
            if (!persistent) {
                closeConnection();
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
                l.add(Char.toDebugString((char) bytes[i]));
            }
            
            return l;
        }
    }
}