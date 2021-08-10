package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpConstants.ReasonPhrase;
import alpha.nomagichttp.HttpConstants.StatusCode;
import alpha.nomagichttp.testutil.TestConstants;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static alpha.nomagichttp.internal.CodeAndPhraseCache.build;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Small tests for {@link CodeAndPhraseCache}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class CodeAndPhraseCacheTest {
    @Test
    void ofStatusLines() {
        CodeAndPhraseCache<String> testee = build(
                String::valueOf, (c, p) -> c + " (" + p + ")");
        
        var exp = TestConstants.statusLines();
        var act = new ArrayList<>();
        for (int i = 0; i < StatusCode.VALUES.length; ++i) {
            var c = StatusCode.VALUES[i];
            var p = ReasonPhrase.VALUES[i];
            act.add(testee.get(c, p));
        }
        
        assertEquals(exp, act);
    }
}