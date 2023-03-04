package alpha.nomagichttp.util;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static java.lang.Long.MAX_VALUE;
import static java.lang.Math.addExact;
import static java.nio.ByteBuffer.wrap;
import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * Is a namespace for things that doesn't belong anywhere.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class Blah
{
    private Blah() {
        // Empty
    }
    
    /**
     * An empty {@code byte[]}.
     */
    public static final byte[] EMPTY_BYTEARRAY = new byte[0];
    
    /**
     * Adds two numbers; capping the result at {@code Long.MAX_VALUE}.
     * 
     * @param a first
     * @param b second
     * 
     * @return the result
     */
    public static long addExactOrMaxValue(long a, long b) {
        try {
            return addExact(a, b);
        } catch (ArithmeticException e) {
            return MAX_VALUE;
        }
    }
    
    /**
     * Adds three numbers; capping the result at {@code Long.MAX_VALUE}.
     * 
     * @param a first
     * @param b second
     * @param c third
     * 
     * @return the result
     */
    public static long addExactOrMaxValue(long a, long b, long c) {
        return addExactOrMaxValue(addExactOrMaxValue(a, b), c);
    }
    
    /**
     * Retrieves the result of the given method.<p>
     * 
     * If the method returns exceptionally, the given resource will be closed.
     * If that close call returns exceptionally, the first exception will
     * propagate having suppressed the latter (as is the case with Java's
     * try-with-resources).
     * 
     * @param method to call
     * @param resource to close
     * @param <T> result type
     * @param <X> exception type
     * 
     * @return what the method returns
     * 
     * @throws X if method throws
     */
    public static <T, X extends Exception> T getOrCloseResource(
          Throwing.Supplier<? extends T, X> method,
          Closeable resource)
          throws X
    {
        try {
            return method.get();
        } catch (Throwable fromMethod) {
            try {
                resource.close();
            } catch (Throwable fromClose) {
                fromMethod.addSuppressed(fromClose);
            }
            throw fromMethod;
        }
    }
}