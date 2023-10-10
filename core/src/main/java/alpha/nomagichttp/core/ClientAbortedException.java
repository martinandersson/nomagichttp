package alpha.nomagichttp.core;

import alpha.nomagichttp.handler.EndOfStreamException;

/**
 * Client shut down his output stream or closed the connection, without sending
 * any request bytes.<p>
 * 
 * Had request bytes been received, then the client abort would instead have
 * resulted in a {@link EndOfStreamException} being thrown.
 */
public final class ClientAbortedException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    
    /**
     * Constructs this object.
     * 
     * @param message passed as-is to {@link Throwable#Throwable(String)}
     */
    public ClientAbortedException(String message) {
        super(message);
    }
}
