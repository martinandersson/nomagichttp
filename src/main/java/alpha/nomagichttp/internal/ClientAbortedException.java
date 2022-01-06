package alpha.nomagichttp.internal;

/**
 * Is the result of a {@link RequestLineSubscriber} if it can be determined
 * that the client closed his output stream cleanly.
 */
final class ClientAbortedException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    
    ClientAbortedException(Throwable cause) {
        super(cause);
    }
}