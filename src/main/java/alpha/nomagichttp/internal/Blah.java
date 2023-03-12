package alpha.nomagichttp.internal;

/**
 * Is a namespace for things that doesn't belong anywhere.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class Blah
{
    private Blah() {
        // Empty
    }
    
    static final String CHANNEL_BLOCKING = "Channel supposed to be blocking";
    
    static void requireVirtualThread() {
        if (!Thread.currentThread().isVirtual()) {
            throw new WrongThreadException("Expected virtual, is platform");
        }
    }
}