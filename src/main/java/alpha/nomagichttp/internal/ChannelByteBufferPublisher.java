package alpha.nomagichttp.internal;

import alpha.nomagichttp.handler.EndOfStreamException;
import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.util.PushPullPublisher;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Flow;
import java.util.stream.IntStream;

import static alpha.nomagichttp.internal.AnnounceToChannel.EOS;
import static alpha.nomagichttp.internal.DefaultServer.becauseChannelOrGroupClosed;
import static alpha.nomagichttp.util.IOExceptions.isCausedByBrokenInputStream;
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
 * signalled a {@link EndOfStreamException}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ChannelByteBufferPublisher implements Flow.Publisher<DefaultPooledByteBufferHolder>
{
    private static final System.Logger LOG
            = System.getLogger(ChannelByteBufferPublisher.class.getPackageName());
    
    // TODO: Repeated on several occurrences in code base; DRY
    private static final String CLOSE_MSG = " Will close the channel's read stream.";
    
    /** Number of bytebuffers in pool. */
    private static final int BUF_COUNT = 5;
    
    /** Size of each pooled bytebuffer (same as jdk.internal.net.http.common.Utils.DEFAULT_BUFSIZE). */
    // If this value ever changes, also change test case "responseBodyBufferOversized"
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
    
    private final DefaultClientChannel chApi;
    private final Deque<ByteBuffer> readable;
    private final PushPullPublisher<DefaultPooledByteBufferHolder> subscriber;
    private final AnnounceToChannel channel;
    
    ChannelByteBufferPublisher(DefaultClientChannel chApi) {
        this.chApi      = chApi;
        this.readable   = new ConcurrentLinkedDeque<>();
        this.subscriber = new PushPullPublisher<>(true, this::pollReadable);
        this.channel    = AnnounceToChannel.read(
                chApi, this::putReadableLast, this::afterChannelFinished);
        
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
            subscriber.stop(new EndOfStreamException());
            readable.clear();
            return null;
        } else if (!b.hasRemaining()) {
            LOG.log(WARNING, () ->
                "Empty ByteBuffer in subscriber's queue. " +
                "Please do not operate on a ByteBuffer after release; can have devastating consequences." +
                CLOSE_MSG);
            subscriber.stop(new IllegalStateException("Empty ByteBuffer"));
            channel.stop();
            chApi.shutdownInputSafe();
            readable.clear();
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
    
    private void afterChannelFinished(long byteCntIgnored, Throwable t) {
        if (t != null) {
            if (!subscriber.stop(t) && shouldRaiseConcern(t)) {
                LOG.log(WARNING, "Failed to deliver this error to a subscriber.", t);
                if (LOG.isLoggable(WARNING) && chApi.isAnythingOpen()) {
                    LOG.log(WARNING, "Closing channel.");
                    
                }
                chApi.closeSafe();
            }
            readable.clear();
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
                if (chApi.isOpenForReading()) {
                    LOG.log(ERROR, () -> "Signalling subscriber failed. " + CLOSE_MSG, exc);
                    chApi.shutdownInputSafe();
                } // else assume whoever closed the stream also logged the exception
            });
        } catch (Throwable t) {
            readable.clear();
            throw t;
        }
    }
    
    @Override
    public void subscribe(Flow.Subscriber<? super DefaultPooledByteBufferHolder> s) {
        subscriber.subscribe(s);
    }
    
    private static boolean shouldRaiseConcern(Throwable t) {
        if (becauseChannelOrGroupClosed(t)) {
            return false;
        }
        if (!(t instanceof IOException)) {
            return true;
        }
        return !isCausedByBrokenInputStream((IOException) t);
    }
}