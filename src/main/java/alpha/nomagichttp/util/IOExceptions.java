package alpha.nomagichttp.util;

import java.io.IOException;

/**
 * Utils for {@link IOException}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class IOExceptions
{
    private IOExceptions() {
        // Empty
    }
    
    // It is possible that these sets will grow and become equal, at which point
    // we'll just keep one, of course. But until then, I'd rather split the
    // messages observed depending on which operation was undertaken.
    
    // Author's observations
    private static final String[] BROKEN_READ = {
            // Windows
            "The specified network name is no longer available",
            "The specified network name is no longer available.", // <-- with an ending dot
            "An existing connection was forcibly closed by the remote host",
            "An established connection was aborted by the software in your host machine",
            // Linux
            "Connection reset by peer"};
    
    // Author's observations
    private static final String[] BROKEN_WRITE = {
            // Windows
            "An existing connection was forcibly closed by the remote host",
            "An established connection was aborted by the software in your host machine",
            "Software caused connection abort: no further information",
            "Connection reset by peer",
            // Linux
            "Broken pipe"};
    
    // In addition, there's (unknown operation, read/write)
    //   "Connection timed out", // Found on Linux NIO1
    //   "Connection reset", // Found on Linux, Java 13
    // Sourced from:
    // https://github.com/http4s/blaze/blob/main/core/src/main/scala/org/http4s/blaze/channel/ChannelHead.scala#L43
    
    /**
     * Returns {@code true} if it is safe to assume that the given exception was
     * caused by a broken input stream (channel read operation failed because
     * other peer shutdown his output stream), otherwise {@code false}.
     * 
     * @param exc to test
     * 
     * @return {@code true} if input stream is broken, otherwise {@code false}
     */
    public static boolean isCausedByBrokenInputStream(IOException exc) {
        return check(exc, BROKEN_READ);
    }
    
    /**
     * Returns {@code true} if it is safe to assume that the given exception was
     * caused by a broken output stream (channel write operation failed because
     * other peer shutdown his input stream), otherwise {@code false}.
     * 
     * @param exc to test
     * 
     * @return {@code true} if input stream is broken, otherwise {@code false}
     */
    public static boolean isCausedByBrokenOutputStream(IOException exc) {
        return check(exc, BROKEN_WRITE);
    }
    
    private static boolean check(IOException exc, String[] against) {
        for (String msg : against) {
            if (msg.equals(exc.getMessage())) {
                return true;
            }
        }
        return false;
    }
}