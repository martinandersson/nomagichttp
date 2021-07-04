package alpha.nomagichttp.util;

import java.io.ByteArrayOutputStream;

/**
 * Exposes the {@code byte[]} and {@code count} fields in {@code
 * ByteArrayOutputStream}.<p>
 * 
 * The purpose is to not perform an "unnecessary" array-copy when retrieving the
 * collected bytes, which is what {@link ByteArrayOutputStream#toByteArray()}
 * do. The {@code toByteArray()} method in this class throws {@link
 * UnsupportedOperationException}. Instead, retrieve a direct reference to the
 * array using {@link #buffer()} and read the valid {@link #count()} of bytes
 * from it.<p>
 * 
 * The buffer can only be retrieved once. To help with garbage collection, the
 * reference is set to null after first access.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class ExposedByteArrayOutputStream extends ByteArrayOutputStream {
    /**
     * Constructs this instance.
     * 
     * @param size the initial size
     */
    public ExposedByteArrayOutputStream(int size) {
        super(size);
    }
    
    /**
     * Returns the number of valid bytes in the buffer.<p>
     * 
     * Warning: Unlike other methods in ByteArrayOutputStream, this method is
     * not synchronized.
     * 
     * @return the number of valid bytes in the buffer
     */
    public int count() {
        return super.count;
    }
    
    /**
     * Returns the buffer reference.<p>
     * 
     * @return the buffer reference
     */
    public byte[] buffer() {
        try {
            return super.buf;
        } finally {
            super.buf = null;
        }
    }
    
    @Override
    public byte[] toByteArray() {
        throw new UnsupportedOperationException();
    }
}