package alpha.nomagichttp.core;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.IdleConnectionException;
import alpha.nomagichttp.handler.ClientChannel;

import java.io.IOException;

import static java.lang.System.Logger.Level.DEBUG;
import static java.util.Objects.requireNonNull;

/**
 * An opinionated {@link DelayedTask} (well, semantically!) throwing
 * {@link IdleConnectionException}.<p>
 * 
 * Unless aborted, a task scheduled by the channel reader will shut down the
 * channel's input stream, or if the writer scheduled the task, it'll shut down
 * the output stream (both share the same instance of this class).<p>
 * 
 * The scheduling request thread must always call {@code abort} after the
 * operation completes (whether normally or exceptionally), which is what may
 * throw an {@code IdleConnectionException}.<p>
 * 
 * It is not specified what happens during a blocking call when the
 * {@code SocketChannel} half-closes. In fact, Javadoc for the {@code read}
 * method falsely claims:
 * <pre>
 *   It is guaranteed, however, that if a channel is in blocking mode and there
 *   is at least one byte remaining in the buffer then this method will block
 *   until at least one byte is read.
 * </pre>
 * 
 * We have a test case, {@code ErrorTest.IdleConnectionExc.duringHead}, which
 * proves that the read method returns normally (with value {@code -1},
 * "end-of-stream"). Technically, <p>
 * 
 * It is not tested and thus unknown what the write method does. Probably, it'll
 * throw some kind of exception. But whatever happens there, it has zero
 * significance. As explained in the Javadoc of {@link IdleConnectionException},
 * a <i>second</i> attempt at writing a response on a channel that just timed
 * out would be quite a dumb thing to do.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
// TODO: Fire event
final class IdleConnTimeout {
    private static final System.Logger LOG
            = System.getLogger(IdleConnTimeout.class.getPackageName());
    
    private final DelayedTask delegate;
    
    private static final int READ = 1, WRITE = 2;
    private int op;
    
    /**
     * Constructs this object.
     * 
     * @param cfg for reaching the idle connection timeout configuration
     * @param api for shutting down a stream on timeout
     * 
     * @throws NullPointerException
     *           if any argument is {@code null}
     */
    IdleConnTimeout(Config cfg, ClientChannel api) {
        requireNonNull(api);
        delegate = new DelayedTask(cfg.timeoutIdleConnection(), () -> {
            if (op == READ && api.isInputOpen()) {
                LOG.log(DEBUG, "Idle connection; shutting down read stream");
                api.shutdownInput();
            } else if (op == WRITE && api.isOutputOpen()) {
                LOG.log(DEBUG, "Idle connection; shutting down write stream");
                api.shutdownOutput();
            } // else nop
        });
    }
    
    /**
     * Starts the timer.
     * 
     * @see DelayedTask#schedule()
     */
    void scheduleRead() {
        delegate.schedule(() -> op = READ);
    }
    
    /**
     * Starts the timer.
     * 
     * @see DelayedTask#schedule()
     */
    void scheduleWrite() {
        delegate.schedule(() -> op = WRITE);
    }
    
    /**
     * Calls {@link DelayedTask#tryAbort()}.
     * 
     * @apiNote
     * The timeout may occur just after the channel operation returns
     * successfully, but before this method is called, which would then throw
     * {@code IdleConnectionException}. This is okay; the timeout
     * <i>did happen</i> and takes precedence.
     * 
     * @param beforeThrowing
     *          optional callback before throwing (may be {@code null})
     * 
     * @throws IdleConnectionException
     *           if {@code tryAbort()} returns {@code false}
     */
    void abort(Runnable beforeThrowing) {
        if (!delegate.tryAbort()) {
            if (beforeThrowing != null) {
                beforeThrowing.run();
            }
            throw new IdleConnectionException();
        }
    }
    
    /**
     * Calls {@link #abort(Runnable)}.<p>
     * 
     * If {@code abort()} throws a throwable, it is intercepted, and the given
     * exception is added to it as suppressed, then the throwable is
     * rethrown.<p>
     * 
     * This method is useful when attempting to abort the task in a context
     * where another exception was caught.
     * 
     * @apiNote
     * If the timeout happened during the channel operation, that operation may
     * return exceptionally, and semantically, the <i>cause</i> was the timeout
     * task having shut down the stream.<p>
     * 
     * It is, however, not specified what exception comes out of the channel
     * when the channel is half-closed, so we couldn't deterministically add a
     * cause to the channel exception, nor is it desired; we rather handle an
     * {@code IdleConnectionException}, which takes precedence even if the
     * channel exception is unrelated. Therefore, the channel exception is
     * marked as suppressed.
     * 
     * @param suppressed see Javadoc
     * 
     * @throws NullPointerException
     *           if {@code suppressed} is {@code null}
     * @throws IdleConnectionException
     *           from {@link #abort(Runnable)}
     */
    void abort(IOException suppressed) {
        requireNonNull(suppressed);
        try {
            abort((Runnable) null);
        } catch (Throwable t) {
            t.addSuppressed(suppressed);
            throw t;
        }
    }
}
