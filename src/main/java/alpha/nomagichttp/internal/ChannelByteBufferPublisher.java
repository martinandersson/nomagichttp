package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.message.Request;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Flow;
import java.util.stream.IntStream;

import static alpha.nomagichttp.internal.AnnounceToChannel.EOS;
import static alpha.nomagichttp.message.ClosedPublisherException.SIGNAL_FAILURE;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;

/**
 * A publisher of bytebuffers read from an asynchronous socket channel.<p>
 * 
 * Many aspects of how to consume published bytebuffers has been documented in
 * {@link Request.Body} and {@link PooledByteBufferHolder}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ChannelByteBufferPublisher implements Flow.Publisher<DefaultPooledByteBufferHolder>, Closeable
{
    private static final System.Logger LOG
            = System.getLogger(ChannelByteBufferPublisher.class.getPackageName());
    
    // TODO: Repeated on several occurrences in code base; DRY
    private static final String CLOSE_MSG = " Will close the channel.";
    
    private static final int
            /** Number of bytebuffers in pool. */
            BUF_COUNT = 5,
            /** Size of each pooled bytebuffer (same as jdk.internal.net.http.common.Utils.DEFAULT_BUFSIZE). */
            BUF_SIZE = 16 * 1_024;
    
    /*
     * Works like this:
     * 
     * 1) When a bytebuffer has been read from the channel, it will be put in a
     * queue of readable bytebuffers, from which the subscriber polls.
     * 
     * 2) When the subscriber releases a bytebuffer, the buffer will be given
     * back to the channel for new read operations.
     */
    
    private final DefaultServer             server;
    private final AsynchronousSocketChannel child;
    private final Deque<ByteBuffer>         readable;
    private final AnnounceToSubscriber<DefaultPooledByteBufferHolder> subscriber;
    private final AnnounceToChannel         channel;
    
    ChannelByteBufferPublisher(DefaultServer server, AsynchronousSocketChannel child) {
        this.server     = server;
        this.child      = child;
        this.readable   = new ConcurrentLinkedDeque<>();
        this.subscriber = new AnnounceToSubscriber<>(this::pollReadable);
        this.channel    = AnnounceToChannel.read(
                child, this::putReadableLast, server, this::afterChannelFinished);
        
        IntStream.generate(() -> BUF_SIZE)
                .limit(BUF_COUNT)
                .mapToObj(ByteBuffer::allocateDirect)
                .forEach(channel::announce);
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
            channel.announce(b);
        }
    }
    
    private void afterChannelFinished(AsynchronousSocketChannel ignored1, long ignored2, Throwable t) {
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
    
    private void subscriberAnnounce() {
        try {
            subscriber.announce(t -> {
                if (child.isOpen()) {
                    LOG.log(ERROR, () -> SIGNAL_FAILURE + CLOSE_MSG, t);
                    server.orderlyShutdown(child);
                } // else assume whoever closed the channel also logged the exception
            });
        } catch (Throwable t) {
            close();
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
    }
}