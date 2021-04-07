package alpha.nomagichttp.util;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal.MethodName.ON_COMPLETE;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal.MethodName.ON_NEXT;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.Signal.MethodName.ON_SUBSCRIBE;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.drainItems;
import static alpha.nomagichttp.testutil.MemorizingSubscriber.drainMethods;
import static java.net.http.HttpRequest.BodyPublisher;
import static java.net.http.HttpRequest.BodyPublishers.ofByteArray;
import static java.net.http.HttpRequest.BodyPublishers.ofString;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Small tests of {@link BetterBodyPublishers}.
 *
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class BetterBodyPublishersTest
{
    @Test
    void emptyArray() {
        BodyPublisher p = ofByteArray(new byte[0]);
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
    void oneByte() {
        final byte[] data = new byte[1];
        
        BodyPublisher p = ofByteArray(data);
        assertThat(p.contentLength()).isOne();
        
        assertThat(drainMethods(p)).containsExactly(
                ON_SUBSCRIBE,
                ON_NEXT,
                ON_COMPLETE);
        
        assertThat(drainItems(p)).containsExactly(ByteBuffer.wrap(data));
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
                ByteBuffer.wrap(new byte[]{2, 3}));
    }
}