package alpha.nomagichttp.events;

/**
 * A container of elapsed time and a byte count of a byte-processing task.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public abstract class AbstractByteCountedStats extends AbstractStats
{
    private final long bytes;
    
    /**
     * Constructs this object.
     * 
     * @param start {@link System#nanoTime()} on start
     * @param stop  {@link System#nanoTime()} on stop
     * @param bytes processed
     */
    public AbstractByteCountedStats(long start, long stop, long bytes) {
        super(start, stop);
        this.bytes = bytes;
    }
    
    /**
     * Returns the number of bytes processed.
     * 
     * @return the number of bytes processed
     */
    public final long bytes() {
        return bytes;
    }
    
    private int hash;
    private boolean hashIsZero;
    
    @Override
    public int hashCode() {
        // Copy-paste from String.hashCode()
        int h = hash;
        if (h == 0 && !hashIsZero) {
            h = Long.hashCode(bytes) + super.hashCode();
            if (h == 0) {
                hashIsZero = true;
            } else {
                hash = h;
            }
        }
        return h;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null ||
            this.getClass() != obj.getClass()) {
            return false;
        }
        var other = (AbstractByteCountedStats) obj;
        return this.bytes == other.bytes &&
               this.nanoTimeOnStart() == other.nanoTimeOnStart() &&
               this.nanoTimeOnStop()  == other.nanoTimeOnStop();
    }
}