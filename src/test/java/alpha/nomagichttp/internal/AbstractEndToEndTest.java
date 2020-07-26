package alpha.nomagichttp.internal;

import alpha.nomagichttp.ExceptionHandler;
import alpha.nomagichttp.Server;
import alpha.nomagichttp.ServerConfig;
import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.route.RouteBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.channels.NetworkChannel;
import java.nio.channels.SocketChannel;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static alpha.nomagichttp.handler.Handlers.noop;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;
import static java.net.InetAddress.getLoopbackAddress;
import static java.nio.ByteBuffer.allocate;
import static java.nio.ByteBuffer.wrap;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Abstract class to help facilitate end-to-end testing by using
 * <i>{@code writeReadXXX}</i>-provided methods.<p>
 * 
 * Any exchange taking 3 seconds or longer will be interrupted and consequently
 * fail the test.<p>
 * 
 * Note: This class provides low-level access for test cases that needs direct
 * control over what bytes are put on the wire and what is received. Test cases
 * that operate on a higher "HTTP exchange semantics kind of layer" should use a
 * client such as JDK's {@link HttpClient} instead.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
abstract class AbstractEndToEndTest
{
    private static final System.Logger LOG = System.getLogger(AbstractEndToEndTest.class.getPackageName());
    
    private static Server server;
    private static NetworkChannel listener;
    private static int port;
    private static volatile Throwable lastExc;
    private static ScheduledExecutorService scheduler;
    
    @BeforeAll
    static void setup() throws IOException {
        Route route = new RouteBuilder("/")
                .handler(noop())
                .build();
        
        server = Server.with(ServerConfig.DEFAULT, Set.of(route), exc -> {
            lastExc = exc;
            // TODO: Default error handler should log
            LOG.log(ERROR, "Unhandled exception.", exc);
            return ExceptionHandler.DEFAULT.apply(exc);
        });
        
        listener = server.start();
        
        port = ((InetSocketAddress) listener.getLocalAddress()).getPort();
        
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
    }
    
    @AfterAll
    static void stop() throws IOException, InterruptedException {
        if (listener != null) {
            // TODO: Use official Server.stop() instead
            listener.close();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler.awaitTermination(1, SECONDS);
        }
    }
    
    protected static Server server() {
        return server;
    }
    
    protected final String writeReadText(String request, String responseEnd)
            throws IOException, InterruptedException
    {
        byte[] bytes = writeReadBytes(
                request.getBytes(US_ASCII),
                responseEnd.getBytes(US_ASCII));
        
        return new String(bytes, US_ASCII);
    }
    
    protected final byte[] writeReadBytes(byte[] request, byte[] responseEnd)
            throws IOException, InterruptedException
    {
        final Thread worker = Thread.currentThread();
        final AtomicBoolean communicating = new AtomicBoolean(true);
        
        ScheduledFuture<?> interrupt = scheduler.schedule(() -> {
            if (communicating.get()) {
                LOG.log(WARNING, "HTTP exchange took too long, will timeout.");
                worker.interrupt();
            }
        }, 3, SECONDS);
        
        try (SocketChannel client = SocketChannel.open(
                new InetSocketAddress(getLoopbackAddress(), port)))
        {
            int r = client.write(wrap(request));
            assertThat(r).isEqualTo(request.length);
            
            FiniteByteBufferSink sink = new FiniteByteBufferSink(128, responseEnd);
            ByteBuffer buff = allocate(128);
            
            while (!worker.isInterrupted() &&
                    !sink.hasReachedEnd()   &&
                    client.read(buff) != -1)
            {
                buff.flip();
                sink.write(buff);
                buff.clear();
            }
            
            if (Thread.interrupted()) { // clear flag
                throw new InterruptedException();
            }
            
            return sink.toByteArray();
        }
        finally {
            communicating.set(false);
            interrupt.cancel(false);
        }
    }
    
    private static class FiniteByteBufferSink {
        private final ByteArrayOutputStream delegate;
        private final byte[] eos;
        private int matched;
        
        FiniteByteBufferSink(int initialSize, byte[] endOfSink) {
            delegate = new ByteArrayOutputStream(initialSize);
            eos = endOfSink;
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
            if (b == eos[matched]) {
                ++matched;
            } else {
                matched = 0;
            }
        }
        
        boolean hasReachedEnd() {
            return matched == eos.length;
        }
        
        byte[] toByteArray() {
            return delegate.toByteArray();
        }
        
        private static String dump(byte[] bytes, int start, int end) {
            StringBuilder b = new StringBuilder();
            
            for (int i = start; i < end; ++i) {
                b.append(bytes[i]).append(" ");
            }
            
            return b.toString();
        }
    }
}