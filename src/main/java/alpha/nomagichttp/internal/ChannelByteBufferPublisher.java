package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.ClosedPublisherException;
import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.message.Request;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousByteChannel;
import java.nio.channels.CompletionHandler;
import java.util.Deque;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;
import java.util.stream.IntStream;

import static java.lang.System.Logger.Level.DEBUG;
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
    
    /** Sentinel value indicating the publisher has finished and must be closed. */
    private static final ByteBuffer DELAYED_CLOSE = ByteBuffer.allocate(0);
    
    /*
     * When a bytebuffer has been read from the channel, it will be put in a
     * queue of readable bytebuffers, from which the subscriber polls.
     * 
     * When the subscriber releases a bytebuffer, the buffer will be put in a
     * queue of writable buffers, from which channel read operations polls.
     */
    
    private final DefaultServer           server;
    private final AsynchronousByteChannel channel;
    private final Deque<ByteBuffer>       readable;
    private final Queue<ByteBuffer>       writable;
    private final SeriallyRunnable        readOp;
    private final ReadHandler             handler;
    private final AnnounceToSubscriber<DefaultPooledByteBufferHolder> subscriber;
    
    ChannelByteBufferPublisher(DefaultServer server, AsynchronousByteChannel channel) {
        this.server     = server;
        this.channel    = channel;
        this.readable   = new ConcurrentLinkedDeque<>();
        this.writable   = new ConcurrentLinkedQueue<>();
        this.readOp     = new SeriallyRunnable(this::readImpl, true);
        this.handler    = new ReadHandler();
        this.subscriber = new AnnounceToSubscriber<>(this::pollReadable);
        
        IntStream.generate(() -> BUF_SIZE)
                .limit(BUF_COUNT)
                .mapToObj(ByteBuffer::allocateDirect)
                .forEach(this::putWritableLast);
    }
    
    @Override
    public void subscribe(Flow.Subscriber<? super DefaultPooledByteBufferHolder> s) {
        subscriber.register(s);
    }
    
    private DefaultPooledByteBufferHolder pollReadable() {
        final ByteBuffer b = readable.poll();
        
        if (b == null) {
            return null;
        } else if (b == DELAYED_CLOSE) {
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
        
        return new DefaultPooledByteBufferHolder(b, readCountIgnored -> afterRelease(b));
    }
    
    private void afterRelease(ByteBuffer b) {
        if (b.hasRemaining()) {
            putReadableFirst(b, true);
        } else {
            putWritableLast(b);
        }
    }
    
    private void putReadableFirst(ByteBuffer b, boolean announce) {
        readable.addFirst(b);
        if (announce) {
            subscriberAnnounce();
        }
    }
    
    private void putReadableLast(ByteBuffer b, boolean announce) {
        readable.addLast(b);
        if (announce) {
            subscriberAnnounce();
        }
    }
    
    private void putWritableLast(ByteBuffer b) {
        writable.add(b.clear());
        readOp.run();
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
    
    private void readImpl() {
        final ByteBuffer buf = writable.poll();
        
        if (buf == null || !channel.isOpen()) {
            // Nothing to do, complete immediately
            readOp.complete();
            return;
        }
        
        try {
            channel.read(buf, buf, handler);
        } catch (Throwable t) {
            handler.failed(t, null);
        }
    }
    
    @Override
    public void close() {
        subscriber.stop();
        server.orderlyShutdown(channel);
        readable.clear();
        writable.clear();
    }
    
    private final class ReadHandler implements CompletionHandler<Integer, ByteBuffer>
    {
        @Override
        public void completed(Integer result, ByteBuffer buff) {
            switch (result) {
                case -1:
                    LOG.log(DEBUG, "End of stream; other side must have closed.");
                    server.orderlyShutdown(channel); // <-- this we have no reason to delay lol
                    putReadableLast(DELAYED_CLOSE, false);
                    writable.clear(); // <-- not really necessary
                    break;
                case 0:
                    String msg =
                        "AsynchronousByteChannel.read() should read at least 1 byte " +
                        "(all our writable buffers has remaining).";
                    LOG.log(ERROR, msg);
                    throw new AssertionError(msg);
                default:
                    assert result > 0;
                    putReadableLast(buff.flip(), false);
                    // 1. Schedule a new channel-read operation
                    //    (do not yet announce to subscriber, see note)
                    readOp.run();
            }
            
            // 2. Complete current logical run and possibly initiate a new read operation
            readOp.complete();
            
            // 3. Announce the availability to subscriber
            subscriberAnnounce();
            
            /*
             * Note: we have to make a choice between attempting to initiate a
             * new channel read operation first before announcing to the
             * subscriber or the other way around. Currently, we do the former.
             * 
             * The goal is to increase throughput, so it's really a matter of
             * trying to make a guess as to who is the most likely to complete
             * the fastest or most likely to be asynchronous. This guy should
             * run first and therefore delay the other guy the smallest amount
             * of time.
             * 
             * We have no guarantees about the subscriber at all versus the
             * actual read operation of the channel is guaranteed to be
             * asynchronous.
             * 
             * AsynchronousChannelGroup's JavaDoc's "threading" section writes;
             * "the completion handler may be invoked directly by the initiating
             * thread" only when "an I/O operation completes immediately".
             * 
             * The question remains through; what if our read handler gets
             * called recursively by the initiating thread? SeriallyRunnable
             * guarantees that the second-level call to "readOp.complete()" does
             * not block/recurse but instead returns immediately. And what
             * happens then right after? "subscriber.announce()"! TODO: TEST
             * 
             * Conclusion: If initiating a new read operation returns
             * immediately - great. We scheduled work for the channel and
             * proceeded to announce, all according to plan. In the "worst case"
             * scenario, the read handler recurse only one level followed by an
             * immediate announce. So, bottom line is that the subscriber is
             * never substantially delayed.
             * 
             * TODO: If initiating a read operation completes immediately, we
             * want to keep initializing until it doesn't (or we run out of
             * buffers). That's the only way to guarantee that the channel is
             * actually put to work before moving on to announcing. I believe
             * the only thing we need to do is to make sure the read handler's
             * second-level call announce() is NOP and only done at first-level
             * - sort of a "delayed announcement".
             */
        }
        
        @Override
        public void failed(Throwable t, ByteBuffer ignored) {
            LOG.log(ERROR, () -> "Channel read operation failed." + CLOSE_MSG, t);
            subscriber.error(t);
            close();
            readOp.complete(); // <-- not really necessary
        }
    }
}