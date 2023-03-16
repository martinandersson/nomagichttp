package alpha.nomagichttp.util;

import java.io.Closeable;

import static java.lang.Math.addExact;

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
     * Adds two numbers.<p>
     * 
     * The implementation uses {@link Math#addExact(int, int)}, but instead of
     * throwing an {@link ArithmeticException}, {@code Integer.MIN_VALUE} or
     * {@code MAX_VALUE} is returned.
     * 
     * @param a first
     * @param b second
     * 
     * @return the result
     */
    public static int addExactOrCap(int a, int b) {
        try {
            return addExact(a, b);
        } catch (ArithmeticException e) {
            return a < 0 && b < 0 ?
                Integer.MIN_VALUE : Integer.MAX_VALUE;
        }
    }
    
    /**
     * Adds two numbers.<p>
     * 
     * The implementation uses {@link Math#addExact(long, long)}, but instead of
     * throwing an {@link ArithmeticException}, {@code Long.MIN_VALUE} or
     * {@code MAX_VALUE} is returned.
     * 
     * @param a first
     * @param b second
     * 
     * @return the result
     */
    public static long addExactOrCap(long a, long b) {
        try {
            return addExact(a, b);
        } catch (ArithmeticException e) {
            return a < 0 && b < 0 ?
                Long.MIN_VALUE : Long.MAX_VALUE;
        }
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
    
    /**
     * Runs the given method.<p>
     * 
     * If the method returns exceptionally, the given resource will be closed.
     * If that close call returns exceptionally, the first exception will
     * propagate having suppressed the latter (as is the case with Java's
     * try-with-resources).
     * 
     * @param method to call
     * @param resource to close
     * @param <X> exception type
     * 
     * @throws X if method throws
     */
    public static <X extends Exception> void runOrCloseResource(
          Throwing.Runnable<X> method,
          Closeable resource)
          throws X
    {
        try {
            method.run();
        } catch (Throwable fromMethod) {
            try {
                resource.close();
            } catch (Throwable fromClose) {
                fromMethod.addSuppressed(fromClose);
            }
            throw fromMethod;
        }
    }
    
    /**
     * Returns {@code v} as an integer; capping the result at
     * {@code Integer.MAX_VALUE}
     * 
     * @param v to cast to an integer
     * 
     * @return the result
     */
    public static int toIntOrMaxValue(long v) {
        try {
            return Math.toIntExact(v);
        } catch (ArithmeticException e) {
            return Integer.MAX_VALUE;
        }
    }
}