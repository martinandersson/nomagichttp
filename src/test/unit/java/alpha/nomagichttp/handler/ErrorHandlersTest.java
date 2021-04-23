package alpha.nomagichttp.handler;


import alpha.nomagichttp.message.Request;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Small tests for {@link ErrorHandlers}
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class ErrorHandlersTest
{
    @Test
    void delayedRetrier() throws Throwable {
        Request req = mock(Request.class);
        Request.Attributes attr = mock(Request.Attributes.class);
        when(req.attributes()).thenReturn(attr);
        when(attr.<Integer>asMapAny()).thenReturn(new ConcurrentHashMap<>());
        
        RequestHandler rh = mock(RequestHandler.class);
        AtomicInteger retries = new AtomicInteger();
        when(rh.logic()).thenReturn((ign,ored) -> {
            retries.incrementAndGet(); });
        
        ErrorHandler testee = ErrorHandlers.delayedRetryOn(RuntimeException.class, 1, 0, 123);
        Throwable err = new RuntimeException();
        try (@SuppressWarnings("rawtypes") MockedStatic<CompletableFuture> fut
                     = Mockito.mockStatic(CompletableFuture.class))
        {
            fut.when(() -> CompletableFuture.delayedExecutor(anyLong(), any()))
                    // Run synchronously
                    .thenReturn((Executor) Runnable::run);
            
            testee.apply(err, null, req, rh);
        }
        
        assertThat(retries.get()).isOne();
        
        assertThatThrownBy(() -> testee.apply(err, null, req, rh))
                .isSameAs(err);
        
        assertThat(retries.get()).isOne();
    }
}
