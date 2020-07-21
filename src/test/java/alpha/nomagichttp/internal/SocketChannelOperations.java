package alpha.nomagichttp.internal;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Adds a convenient API to a SocketChannel.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class SocketChannelOperations implements Closeable
{
    private final static ScheduledExecutorService SCHEDULER
            = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                return t; });
    
    private final SocketChannel delegate;
    
    SocketChannelOperations(SocketChannel delegate) {
        this.delegate = requireNonNull(delegate);
    }
    
    void write(String ascii) throws Exception {
        write(ascii.getBytes(US_ASCII));
    }
    
    void write(byte[] bytes) throws Exception {
        run(() -> {
            ByteBuffer buff = ByteBuffer.wrap(bytes);
            do {
                delegate.write(buff);
            } while (buff.hasRemaining());
            return null;
        });
    }
    
    private <V> V run(Callable<V> operation) throws Exception {
        final Thread worker = Thread.currentThread();
        final AtomicBoolean communicating = new AtomicBoolean(true);
        
        ScheduledFuture<?> interruptTask = SCHEDULER.schedule(() -> {
            if (communicating.get()) {
                worker.interrupt();
            }
        }, 3, SECONDS);
        
        try {
            V v = operation.call();
            interruptTask.cancel(true);
            Thread.interrupted(); // clear flag
            return v;
        }
        catch (ClosedByInterruptException e) {
            Thread.interrupted(); // clear flag
            throw e;
        }
    }
    
    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
