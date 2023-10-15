package alpha.nomagichttp.core;

import java.io.Serial;

final class AfterActionException extends RuntimeException
{
    @Serial
    private static final long serialVersionUID = 1L;
    
    AfterActionException(RuntimeException cause) {
        super(cause);
    }
}