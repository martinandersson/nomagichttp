package alpha.nomagichttp.util;

import alpha.nomagichttp.testutil.MemorizingSubscriber;
import alpha.nomagichttp.testutil.MemorizingSubscriber.Request;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeoutException;

import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_COMPLETE;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_NEXT;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.MethodName.ON_SUBSCRIBE;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.drainItems;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.drainMethods;
import static alpha.nomagichttp.util.BetterBodyPublishers.concat;
import static alpha.nomagichttp.util.BetterBodyPublishers.ofByteArray;
import static alpha.nomagichttp.util.BetterBodyPublishers.ofFile;
import static alpha.nomagichttp.util.BetterBodyPublishers.ofString;
import static java.net.http.HttpRequest.BodyPublisher;
import static java.nio.ByteBuffer.wrap;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Small tests of {@link BetterBodyPublishers}.
 *
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class BetterBodyPublishersTest
{
    @Test
    void emptyArray() {
        BodyPublisher p = ofByteArray(array());
        assertThat(p.contentLength()).isZero();
        assertThat(drainMethods(p)).containsExactly(
                ON_SUBSCRIBE,
                ON_COMPLETE);
    }
    
    @Test
    void emptyString() {
        BodyPublisher p = ofString("");
        assertThat(p.contentLength()).isZero();
        assertThat(drainMethods(p)).containsExactly(
                ON_SUBSCRIBE,
                ON_COMPLETE);
    }
    
    @Test
    void oneByte_reqMax() {
        BodyPublisher p = ofByteArray(array(1));
        assertThat(p.contentLength()).isOne();
        
        assertThat(drainMethods(p)).containsExactly(
                ON_SUBSCRIBE,
                ON_NEXT,
                ON_COMPLETE);
        
        // This subscribes a new subscriber
        assertThat(drainItems(p)).containsExactly(
                wrap(array(1)));
    }
    
    @Test
    void oneByte_reqOne() {
        BodyPublisher p = ofByteArray(array(1));
        assertThat(p.contentLength()).isOne();
        
        var s = new MemorizingSubscriber<>(Request.IMMEDIATELY_N(1));
        p.subscribe(s);
        
        assertThat(s.methodNames()).containsExactly(
                ON_SUBSCRIBE,
                ON_NEXT,
                ON_COMPLETE);
        
        assertThat(s.items()).containsExactly(
                wrap(array(1)));
    }
    
    @Test
    void slice() {
        // Extracting {2, 3}
        final byte[] data = {1, 2, 3, 4};
        
        BodyPublisher p = ofByteArray(data, 1, 2);
        assertThat(p.contentLength()).isEqualTo(2);
        
        assertThat(drainMethods(p)).containsExactly(
                ON_SUBSCRIBE,
                ON_NEXT,
                ON_COMPLETE);
        
        assertThat(drainItems(p)).containsExactly(
                wrap(array(2, 3)));
    }
    
    @Test
    void file() throws IOException, ExecutionException, InterruptedException, TimeoutException {
        Path f = Files.createTempDirectory("nomagic").resolve("f");
        Files.write(f, new byte[]{1});
        assertThat(Files.size(f)).isOne();
        
        Flow.Publisher<ByteBuffer> p = ofFile(f);
        List<MemorizingSubscriber.Signal> s = MemorizingSubscriber.drainSignalsAsync(p)
                .toCompletableFuture().get(1, SECONDS);
        
        assertThat(s).hasSize(3);
        
        assertSame(s.get(0).methodName(), ON_SUBSCRIBE);
        assertSame(s.get(1).methodName(), ON_NEXT);
        assertSame(s.get(2).methodName(), ON_COMPLETE);
        
        ByteBuffer item = s.get(1).argumentAs();
        assertThat(item.remaining()).isOne();
        assertThat(item.get()).isEqualTo((byte) 1);
    }
    
    @Test
    void concat_onlyBodyPublishers() {
        var a = ofByteArray(array(1));
        var b = ofByteArray(array(2));
        var c = concat(a, b);
        
        assertThat(c.contentLength()).isEqualTo(2);
        
        var s = new MemorizingSubscriber<>(Request.IMMEDIATELY_N(2));
        c.subscribe(s);
        
        assertThat(s.methodNames()).containsExactly(
                ON_SUBSCRIBE,
                ON_NEXT,
                ON_NEXT,
                ON_COMPLETE);
        
        assertThat(s.items()).containsExactly(
                wrap(array(1)),
                wrap(array(2)));
    }
    
    @Test
    void concat_mixedPublishers() {
        var a = ofByteArray(array(1));
        var b = Publishers.just(wrap(array(2)));
        var c = concat(a, b);
        
        assertThat(c.contentLength()).isEqualTo(-1);
        
        assertThat(drainMethods(c)).containsExactly(
                ON_SUBSCRIBE,
                ON_NEXT,
                ON_NEXT,
                ON_COMPLETE);
        
        assertThat(drainItems(c)).containsExactly(
                wrap(array(1)),
                wrap(array(2)));
    }
    
    private static byte[] array(int... bytes) {
        byte[] b = new byte[bytes.length];
        for (int i = 0; i < bytes.length; ++i)
            b[i] = (byte) bytes[i];
        return b;
    }
}