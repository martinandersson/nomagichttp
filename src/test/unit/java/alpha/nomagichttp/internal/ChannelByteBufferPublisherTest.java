package alpha.nomagichttp.internal;

import alpha.nomagichttp.testutil.ClientOperations;
import alpha.nomagichttp.testutil.Logging;
import alpha.nomagichttp.testutil.SkeletonServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;

import static java.lang.System.Logger.Level.ALL;
import static java.util.concurrent.Flow.Publisher;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@code ChannelByteBufferPublisher}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class ChannelByteBufferPublisherTest
{
    // TODO: Most of this plumbing is copy-pasted from RequestHeadSubscriberTest.
    //       DRY; refactor to common superclass or something.
    
    private static SkeletonServer SERVER;
    private static ClientOperations CLIENT;
    private ChannelByteBufferPublisher testee;
    
    @BeforeAll
    static void beforeAll() throws IOException {
        Logging.setLevel(ChannelByteBufferPublisher.class, ALL);
        SERVER = new SkeletonServer();
        SERVER.start();
        CLIENT = new ClientOperations(SERVER::newClient);
    }
    
    @AfterAll
    static void afterAll() throws IOException {
        SERVER.close();
    }
    
    ChannelByteBufferPublisher testee() throws InterruptedException {
        if (testee == null) {
            DefaultChannelOperations ops = new DefaultChannelOperations(SERVER.accept());
            testee = new ChannelByteBufferPublisher(ops);
        }
        
        return testee;
    }
    
    /**
     * Making sure two different subsequent subscribers can slice and share one
     * and same source bytebuffer (assuming testee's buffer size is &gt;= 2
     * bytes).
     */
    @Test
    void switch_subscriber_midway() throws IOException, InterruptedException, TimeoutException, ExecutionException {
        // Hopefully this goes into just 1 ByteBuffer
        CLIENT.write("ab");
        
        Publisher<DefaultPooledByteBufferHolder> oneByteOnly = new LengthLimitedOp(1, testee());
        
        // TODO: "finisher" copy-pasted from DefaultRequest impl. DRY.
        BiFunction<byte[], Integer, String> finisher = (buf, count) ->
                new String(buf, 0, count, StandardCharsets.US_ASCII);
        
        HeapSubscriber<String> s1 = new HeapSubscriber<>(finisher);
        oneByteOnly.subscribe(s1);
        
        // But the first subscriber only receives the first letter
        assertThat(get(s1)).isEqualTo("a");
        
        // (right around now, the subscription is cancelled and the bytebuffer recycled)
        
        // A new subscriber subscribes and is re-issued the same bytebuffer,
        // but with a position where the first subscriber left off
        
        oneByteOnly = new LengthLimitedOp(1, testee());
        
        HeapSubscriber<String> s2 = new HeapSubscriber<>(finisher);
        oneByteOnly.subscribe(s2);
        
        assertThat(get(s2)).isEqualTo("b");
    }
    
    private static <R> R get(HeapSubscriber<R> subscriber) throws InterruptedException, ExecutionException, TimeoutException {
        return subscriber.asCompletionStage().toCompletableFuture().get(3, SECONDS);
    }
}