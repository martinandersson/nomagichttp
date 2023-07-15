package alpha.nomagichttp.core;

/**
 * A reusable string builder.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ReusableStringBuilder {
    private final StringBuilder str = new StringBuilder();
    
    /**
     * Append a char to the builder.
     * 
     * @param c to append (byte cast to char) 
     */
    void append(byte c) {
        str.append((char) c);
    }
    
    /**
     * Append a char sequence to the builder.
     * 
     * @param seq to append
     */
    void append(CharSequence seq) {
        str.append(seq);
    }
    
    /**
     * Returns true if the builder has had something appended to it.
     * 
     * @return true if the builder has had something appended to it
     */
    boolean hasAppended() {
        return str.length() > 0;
    }
    
    /**
     * Build the string.<p>
     * 
     * This method will reset the underlying string builder.
     * 
     * @return the string
     */
    String finish() {
        final var v = str.toString();
        str.setLength(0);
        return v;
    }
}