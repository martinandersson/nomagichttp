package alpha.nomagichttp.internal;

import alpha.nomagichttp.Chain;
import alpha.nomagichttp.NonThrowingChain;

final class ErrorHandlerException extends RuntimeException
{
    static NonThrowingChain unchecked(Chain delegate) {
        return () -> {
            try {
                return delegate.proceed();
            } catch (Exception e) {
                throw new ErrorHandlerException(e);
            }
        };
    }
    
    private ErrorHandlerException(Exception cause) {
        super(cause);
    }
}