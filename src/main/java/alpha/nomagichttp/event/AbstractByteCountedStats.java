package alpha.nomagichttp.event;

/**
 * Holder of elapsed time and a count of bytes processed.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
// TODO: Rename to AbstractByteTransferStats
public abstract class AbstractByteCountedStats extends AbstractStats
{
    private final long byteCount;
    
    /**
     * Constructs this object.
     * 
     * @param start {@link System#nanoTime()} on start
     * @param stop  {@link System#nanoTime()} on stop
     * @param byteCount processed
     */
    public AbstractByteCountedStats(long start, long stop, long byteCount) {
        super(start, stop);
        this.byteCount = byteCount;
    }
    
    /**
     * Returns the number of bytes processed.
     * 
     * @return see JavaDoc
     */
    public final long byteCount() {
        return byteCount;
    }
    
    private int hash;
    private boolean hashIsZero;
    
    @Override
    public int hashCode() {
        // Copy-paste from String.hashCode()
        int h = hash;
        if (h == 0 && !hashIsZero) {
            h = Long.hashCode(byteCount) + super.hashCode();
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
        return this.byteCount == other.byteCount &&
               this.nanoTimeOnStart() == other.nanoTimeOnStart() &&
               this.nanoTimeOnStop()  == other.nanoTimeOnStop();
    }
}