package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.ClosedPublisherException;
import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.message.Request;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousByteChannel;
import java.util.Deque;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;
import java.util.stream.IntStream;

import static alpha.nomagichttp.internal.AnnounceToChannel.EOS;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;

/**
 * A publisher of bytebuffers read from an asynchronous byte channel (assumed to
 * not support concurrent read operations).<p>
 * 
 * Many aspects of how to consume published bytebuffers has been documented in
 * {@link Request.Body} and {@link PooledByteBufferHolder}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ChannelByteBufferPublisher implements Flow.Publisher<DefaultPooledByteBufferHolder>, Closeable
{
    private static final System.Logger LOG = System.getLogger(ChannelByteBufferPublisher.class.getPackageName());
    
    private static final String CLOSE_MSG = " Will close channel.";
    
    private static final int
            /** Number of bytebuffers in pool. */
            BUF_COUNT = 5,
            /** Size of each pooled bytebuffer (same as jdk.internal.net.http.common.Utils.DEFAULT_BUFSIZE). */
            BUF_SIZE = 16 * 1_024;
    
    /*
     * When a bytebuffer has been read from the channel, it will be put in a
     * queue of readable bytebuffers, from which the subscriber polls.
     * 
     * When the subscriber releases a bytebuffer, the buffer will be put in a
     * queue of writable buffers, from which channel read operations polls.
     */
    
    private final DefaultServer           server;
    private final AsynchronousByteChannel child;
    private final Deque<ByteBuffer>       readable;
    private final Queue<ByteBuffer>       writable;
    private final AnnounceToSubscriber<DefaultPooledByteBufferHolder> subscriber;
    private final AnnounceToChannel       channel;
    
    ChannelByteBufferPublisher(DefaultServer server, AsynchronousByteChannel child) {
        this.server     = server;
        this.child      = child;
        this.readable   = new ConcurrentLinkedDeque<>();
        this.writable   = new ConcurrentLinkedQueue<>();
        this.subscriber = new AnnounceToSubscriber<>(this::pollReadable);
        this.channel    = AnnounceToChannel.read(
                child, writable::poll, this::afterChannelOp, this::afterChannelFinished, server);
        
        IntStream.generate(() -> BUF_SIZE)
                .limit(BUF_COUNT)
                .mapToObj(ByteBuffer::allocateDirect)
                .forEach(this::putWritableLast);
    }
    
    private DefaultPooledByteBufferHolder pollReadable() {
        final ByteBuffer b = readable.poll();
        
        if (b == null) {
            return null;
        } else if (b == EOS) {
            // Channel dried up
            close();
            return null;
        } else if (!b.hasRemaining()) {
            LOG.log(WARNING, () ->
                "Empty ByteBuffer in subscriber's queue. " +
                "Please do not operate on a ByteBuffer after release; can have devastating consequences." +
                CLOSE_MSG);
            close();
            return null;
        }
        
        return new DefaultPooledByteBufferHolder(b, readCountIgnored -> afterSubscriber(b));
    }
    
    private void afterSubscriber(ByteBuffer b) {
        if (b.hasRemaining()) {
            putReadableFirst(b);
        } else {
            putWritableLast(b);
        }
    }
    
    private void afterChannelOp(ByteBuffer b) {
        putReadableLast(b.flip());
    }
    
    private void afterChannelFinished(AsynchronousByteChannel childIgnored, long byteCountIgnored, Throwable t) {
        if (t != null) {
            subscriber.error(t);
            close();
        } // else normal completion; subscriber will be stopped when EOS is observed
    }
    
    private void putReadableFirst(ByteBuffer b) {
        assert b.hasRemaining();
        readable.addFirst(b);
        subscriberAnnounce();
    }
    
    private void putReadableLast(ByteBuffer b) {
        assert b == EOS || b.hasRemaining();
        readable.addLast(b);
        subscriberAnnounce();
    }
    
    private void putWritableLast(ByteBuffer b) {
        writable.add(b.clear());
        channel.announce();
    }
    
    private void subscriberAnnounce() {
        try {
            subscriber.announce();
        } catch (Throwable t) {
            LOG.log(ERROR, () -> "Signalling Subscriber.onNext() failed." + CLOSE_MSG, t);
            subscriber.error(new ClosedPublisherException(t));
            close();
            // Caller may be application code! (re-throw also documented in Request.Body)
            throw t;
        }
    }
    
    @Override
    public void subscribe(Flow.Subscriber<? super DefaultPooledByteBufferHolder> s) {
        subscriber.register(s);
    }
    
    @Override
    public void close() {
        subscriber.stop();
        channel.stop();
        server.orderlyShutdown(child);
        readable.clear();
        writable.clear();
    }
}