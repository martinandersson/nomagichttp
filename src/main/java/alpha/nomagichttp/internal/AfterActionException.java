package alpha.nomagichttp.internal;

final class AfterActionException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    
    AfterActionException(RuntimeException cause) {
        super(cause);
    }
}