package alpha.nomagichttp.internal;

/**
 * Require a virtual thread.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class VThreads
{
    private VThreads() {
        // Empty
    }
    
    static final String CHANNEL_BLOCKING = "Channel supposed to be blocking";
    
    static void requireVirtualThread() {
        if (!Thread.currentThread().isVirtual()) {
            throw new WrongThreadException("Expected virtual, is platform");
        }
    }
}