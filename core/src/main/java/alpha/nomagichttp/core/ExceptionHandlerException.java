package alpha.nomagichttp.core;

import alpha.nomagichttp.Chain;
import alpha.nomagichttp.NonThrowingChain;

final class ExceptionHandlerException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    
    static NonThrowingChain unchecked(Chain delegate) {
        return () -> {
            try {
                return delegate.proceed();
            } catch (Exception e) {
                throw new ExceptionHandlerException(e);
            }
        };
    }
    
    private ExceptionHandlerException(Exception cause) {
        super(cause);
    }
}
