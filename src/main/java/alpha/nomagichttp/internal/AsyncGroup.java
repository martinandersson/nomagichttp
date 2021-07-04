package alpha.nomagichttp.internal;

import java.io.IOException;
import java.nio.channels.AsynchronousChannelGroup;
import java.util.concurrent.Executors;

/**
 * Manager of a global {@link AsynchronousChannelGroup}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class AsyncGroup
{
    // Good info on async groups:
    // https://openjdk.java.net/projects/nio/resources/AsynchronousIo.html
    
    private static AsynchronousChannelGroup grp = null;
    private static int count = 0;
    
    /**
     * Get existing- or create a new channel group.<p>
     * 
     * This method must be called for each new group member and will internally
     * increment a member count (only if this methods returns normally).
     * 
     * @param nThreads of group
     * @return the group (never {@code null})
     * @throws IOException If an I/O error occurs
     */
    static synchronized AsynchronousChannelGroup register(int nThreads) throws IOException {
        if (grp == null) {
            grp = AsynchronousChannelGroup.withFixedThreadPool(nThreads,
                    // Default-group uses daemon threads, we use non-daemon
                    Executors.defaultThreadFactory());
        }
        ++count;
        return grp;
    }
    
    /**
     * Shutdown the group, if the active member count reaches 0.
     * 
     * @throws IllegalStateException if the member count is 0
     */
    static synchronized void unregister() {
        if (count == 0) {
            throw new IllegalStateException();
        }
        if (--count == 0) {
            grp.shutdown();
            grp = null;
        }
    }
}