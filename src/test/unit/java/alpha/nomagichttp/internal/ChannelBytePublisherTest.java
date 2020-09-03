package alpha.nomagichttp.internal;

import alpha.nomagichttp.test.Logging;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;

import static java.lang.System.Logger.Level.ALL;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@code ChannelBytePublisher}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class ChannelBytePublisherTest
{
    // TODO: Most of this plumbing is copy-pasted from HeadParserTest.
    //       DRY; refactor to common superclass or something.
    
    private static TestServer SERVER;
    private static ClientOperations CLIENT;
    private ChannelBytePublisher testee;
    
    @BeforeAll
    static void beforeAll() throws IOException {
        Logging.setLevel(ChannelBytePublisher.class, ALL);
        SERVER = new TestServer();
        SERVER.start();
        CLIENT = new ClientOperations(SERVER::newClient);
    }
    
    @AfterAll
    static void afterAll() throws IOException {
        SERVER.close();
    }
    
    ChannelBytePublisher testee() throws Throwable {
        if (testee == null) {
            testee = new ChannelBytePublisher(mock(AsyncServer.class), SERVER.accept());
            testee.begin();
        }
        
        return testee;
    }
    
    /**
     * Making sure two different subsequent subscribers can slice and share one
     * and same source bytebuffer (assuming testee's buffer size is >= 2 bytes).
     */
    @Test
    void switch_subscriber_midway() throws Throwable {
        // Hopefully this goes into just 1 ByteBuffer
        CLIENT.write("ab");
        
        LimitedFlow oneByteOnly = new LimitedFlow(1);
        testee().subscribe(oneByteOnly);
        
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
        
        oneByteOnly = new LimitedFlow(1);
        testee().subscribe(oneByteOnly);
        
        HeapSubscriber<String> s2 = new HeapSubscriber<>(finisher);
        oneByteOnly.subscribe(s2);
        
        assertThat(get(s2)).isEqualTo("b");
    }
    
    // TODO: Once we have the common type for "asCompletionStage() we can rely
    //       on that instead of HeapSubscriber.
    private static <R> R get(HeapSubscriber<R> subscriber) throws InterruptedException, ExecutionException, TimeoutException {
        return subscriber.asCompletionStage().toCompletableFuture().get(3, SECONDS);
    }
}