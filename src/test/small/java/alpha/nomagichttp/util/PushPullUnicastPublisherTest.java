package alpha.nomagichttp.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.function.Consumer;

import static alpha.nomagichttp.testutil.TestSubscribers.onNext;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Small tests for {@link PushPullUnicastPublisher}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class PushPullUnicastPublisherTest
{
    @Test
    void reusable() {
        var items = new ArrayList<String>();
        var testee = PushPullUnicastPublisher.reusable(() -> "hello");
        Consumer<String> pushAndComplete = str -> {
            items.add(str);
            testee.complete();
        };
        testee.subscribe(onNext(pushAndComplete));
        testee.subscribe(onNext(pushAndComplete));
        assertThat(items).containsExactly("hello", "hello");
    }
}