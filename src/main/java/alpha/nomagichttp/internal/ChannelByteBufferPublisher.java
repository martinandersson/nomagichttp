package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.message.Request;

import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousByteChannel;
import java.nio.channels.CompletionHandler;
import java.util.Deque;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.IntStream;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;

/**
 * Publishes bytebuffers read from an asynchronous byte channel (assumed to not
 * support concurrent read operations).<p>
 * 
 * Many aspects of how to consume published bytebuffers has been documented in
 * {@link Request.Body} and {@link PooledByteBufferHolder}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see AbstractUnicastPublisher
 */
final class ChannelByteBufferPublisher extends AbstractUnicastPublisher<DefaultPooledByteBufferHolder>
{
    private static final System.Logger LOG = System.getLogger(ChannelByteBufferPublisher.class.getPackageName());
    
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
    
    private final AsyncServer             server;
    private final AsynchronousByteChannel channel;
    private final Deque<ByteBuffer>       readable;
    private final Queue<ByteBuffer>       writable;
    private final SeriallyRunnable        readOp;
    private final ReadHandler             handler;
    
    ChannelByteBufferPublisher(AsyncServer server, AsynchronousByteChannel channel) {
        this.server   = server;
        this.channel  = channel;
        this.readable = new ConcurrentLinkedDeque<>();
        this.writable = new ConcurrentLinkedQueue<>();
        this.readOp   = new SeriallyRunnable(this::readImpl, true);
        this.handler  = new ReadHandler();
        
        IntStream.generate(() -> BUF_SIZE)
                .limit(BUF_COUNT)
                .mapToObj(ByteBuffer::allocateDirect)
                .forEach(writable::add);
        
        readOp.run();
    }
    
    @Override
    protected DefaultPooledByteBufferHolder poll() {
        final ByteBuffer b = readable.poll();
        
        if (b == null) {
            return null;
        } else if (b == DELAYED_CLOSE) {
            close();
            return null;
        }
        
        return new DefaultPooledByteBufferHolder(b, (b2, read) -> release(b2));
    }
    
    private void release(ByteBuffer b) {
        if (b.hasRemaining()) {
            readable.addFirst(b);
            announce();
        } else {
            writable.add(b.clear());
            readOp.run();
        }
    }
    
    @Override
    protected void failed(DefaultPooledByteBufferHolder buf) {
        buf.release();
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
        try {
            super.close();
        } finally {
            server.orderlyShutdown(channel);
            writable.clear();
            readable.clear();
        }
    }
    
    private final class ReadHandler implements CompletionHandler<Integer, ByteBuffer>
    {
        @Override
        public void completed(Integer result, ByteBuffer buff) {
            try {
                switch (result) {
                    case -1:
                        LOG.log(DEBUG, "End of stream; other side must have closed.");
                        server.orderlyShutdown(channel);
                        readable.add(DELAYED_CLOSE);
                        break;
                    case 0:
                        LOG.log(ERROR, "Buffer wasn't writable. Fake!");
                        close();
                        return;
                    default:
                        assert result > 0;
                        readable.add(buff.flip());
                        // 1. Schedule a new read operation
                        //    (do not announce(), see note)
                        readOp.run();
                }
            } finally {
                // 2. Complete current logical run and possibly initiate a new read operation
                readOp.complete();
            }
            
            // 3. Announce the availability to subscriber
            announce();
            
            // Note: We could have announced the bytebuffer immediately after
            // putting it into the readable queue. This wouldn't have been
            // "wrong" or failed the application. Certainly, the subscriber
            // would have received the bytebuffer sooner.
            // 
            // But there is no guarantee the subscriber receiving the bytebuffer
            // won't block or otherwise take time to process it. And if he does,
            // this is time the channel could potentially be sitting around doing
            // nothing but waiting on a new read operation. So we much rather have
            // both do work in order to increase throughput.
            // 
            // Initiating a new read operation before announcing assumes then
            // that initiating is "guaranteed" to be really fast since our only
            // intention here is to increase throughput at what is conceivably
            // no significant cost for the subscriber.
            // 
            // We assume that performing this read initialization is really fast
            // for a couple of reasons:
            // 
            // 1) AsynchronousChannelGroup's JavaDoc's "threading" section writes;
            //    "the completion handler may be invoked directly by the
            //    initiating thread" only when "an I/O operation completes
            //    immediately". So, asynchronous or not, the channel implementation
            //    itself will not impose any significant delay. The question is,
            //    how would our code behave if the channel implementation call
            //    our read handler immediately on the initiating thread?
            // 
            // 2) The top-level call to readOp.complete() could effectively
            //    be stuck in a loop initiating read operations
            //    (readOp.complete() -> readOp.run() -> this.readImpl()) - but,
            //    not recursively as guaranteed by SeriallyRunnable. If the
            //    top-level's request to initiate a read operation completes
            //    immediately and the read handler is called immediately, then
            //    that second-level call to "readOp.complete()" will also return
            //    immediately and what happens then right after? announce()!
            // 
            // Conclusion: If the read operation is asynchronous - great. We
            // scheduled work for the channel and proceeded to announce, all
            // according to plan. In the "worst case", a new bytebuffer is
            // immediately available from the channel so unfortunately no "real
            // work" was scheduled but at least we will effectively proceed to
            // announce so that the subscriber is delayed no more.
            // 
            // TODO: As described in the conclusion - we only cover for the best
            // case scenario; the operation is async. But ideally, it would be
            // great if we repeatedly initiated new immediately-completed read
            // operations until we fully drained the channel of available bytes
            // and then one more read request - the async one - and then do the
            // potentially blocking announcement (repetitions limited to
            // the numbers of buffers in our pool of course, already provided
            // for in readImpl()). I believe the only thing we need to do is to
            // make sure the read handler's second-level call announce() is NOP
            // and only done at first-level - sort of a "delayed announcement".
        }
        
        @Override
        public void failed(Throwable t, ByteBuffer ignored) {
            try {
                LOG.log(ERROR, "Channel read operation failed. Will close channel.", t);
                close();
            } finally {
                readOp.complete();
            }
        }
    }
}