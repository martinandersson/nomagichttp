package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.ClosedPublisherException;
import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.util.PushPullPublisher;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Flow;
import java.util.stream.IntStream;

import static alpha.nomagichttp.internal.AnnounceToChannel.EOS;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;

/**
 * A unicast publisher of bytebuffers read from an asynchronous socket
 * channel.<p>
 * 
 * Many aspects of how to consume published bytebuffers has been documented in
 * {@link Request.Body} and {@link PooledByteBufferHolder}.<p>
 * 
 * When the channel's end-of-stream is reached, the active subscriber will be
 * signalled a {@link ClosedPublisherException} with the message "EOS".
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ChannelByteBufferPublisher implements Flow.Publisher<DefaultPooledByteBufferHolder>, Closeable
{
    private static final System.Logger LOG
            = System.getLogger(ChannelByteBufferPublisher.class.getPackageName());
    
    // TODO: Repeated on several occurrences in code base; DRY
    private static final String CLOSE_MSG = " Will close the channel's read stream.";
    
    /** Number of bytebuffers in pool. */
    private static final int BUF_COUNT = 5;
    
    /** Size of each pooled bytebuffer (same as jdk.internal.net.http.common.Utils.DEFAULT_BUFSIZE). */
    /* package-private */ static final int BUF_SIZE = 16 * 1_024;
    
    /*
     * Works like this:
     * 
     * 1) When a bytebuffer has been read from the channel, it will be put in a
     * queue of readable bytebuffers, from which the subscriber polls.
     * 
     * 2) When the subscriber releases a bytebuffer, the buffer will be given
     * back to the channel for new read operations.
     */
    
    private final DefaultClientChannel child;
    private final Deque<ByteBuffer> readable;
    private final PushPullPublisher<DefaultPooledByteBufferHolder> subscriber;
    private final AnnounceToChannel channel;
    
    ChannelByteBufferPublisher(DefaultClientChannel child) {
        this.child      = child;
        this.readable   = new ConcurrentLinkedDeque<>();
        this.subscriber = new PushPullPublisher<>(true, this::pollReadable);
        this.channel    = AnnounceToChannel.read(
                child, this::putReadableLast, this::afterChannelFinished);
        
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
            subscriber.error(new ClosedPublisherException("EOS"));
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
        
        return new DefaultPooledByteBufferHolder(
                b, readCountIgnored -> afterSubscriberPipeline(b));
    }
    
    private void afterSubscriberPipeline(ByteBuffer b) {
        if (b.hasRemaining()) {
            putReadableFirst(b);
        } else {
            channel.announce(b);
        }
    }
    
    private void afterChannelFinished(DefaultClientChannel ignored1, long ignored2, Throwable t) {
        if (t != null) {
            subscriber.error(new ClosedPublisherException("Channel failure.", t));
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
            subscriber.announce(exc -> {
                if (child.isOpenForReading()) {
                    LOG.log(ERROR, () -> "Signalling subscriber failed. " + CLOSE_MSG, exc);
                    child.shutdownInputSafe();
                } // else assume whoever closed the stream also logged the exception
            });
        } catch (Throwable t) {
            close();
        }
    }
    
    @Override
    public void subscribe(Flow.Subscriber<? super DefaultPooledByteBufferHolder> s) {
        subscriber.subscribe(s);
    }
    
    @Override
    public void close() {
        subscriber.stop();
        channel.stop();
        child.shutdownInputSafe();
        readable.clear();
    }
}