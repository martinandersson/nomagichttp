package alpha.nomagichttp.testutil;

import alpha.nomagichttp.util.BetterBodyPublishers;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeoutException;

import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal.MethodName.ON_COMPLETE;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal.MethodName.ON_NEXT;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal.MethodName.ON_SUBSCRIBE;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Small tests for {@link BetterBodyPublishers}
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class BetterBodyPublishersTest {
    @Test
    void ofFile() throws IOException, ExecutionException, InterruptedException, TimeoutException {
        Path f = Files.createTempDirectory("nomagic").resolve("f");
        Files.write(f, new byte[]{1});
        assertThat(Files.size(f)).isOne();
        
        Flow.Publisher<ByteBuffer> p = BetterBodyPublishers.ofFile(f);
        List<Signal> s = MemorizingSubscriber.drainSignalsAsync(p)
                .toCompletableFuture().get(3, SECONDS);
        
        assertThat(s).hasSize(3);
        
        assertSame(s.get(0).getMethodName(), ON_SUBSCRIBE);
        assertSame(s.get(1).getMethodName(), ON_NEXT);
        assertSame(s.get(2).getMethodName(), ON_COMPLETE);
        
        ByteBuffer item = s.get(1).getArgument();
        assertThat(item.remaining()).isOne();
        assertThat(item.get()).isEqualTo((byte) 1);
    }
}