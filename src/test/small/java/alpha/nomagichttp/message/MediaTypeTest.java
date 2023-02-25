package alpha.nomagichttp.message;

import alpha.nomagichttp.testutil.Logging;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.logging.Handler;

import static alpha.nomagichttp.message.MediaType.TEXT_PLAIN;
import static alpha.nomagichttp.message.MediaType.TEXT_PLAIN_UTF8;
import static alpha.nomagichttp.message.MediaType.ALL;
import static alpha.nomagichttp.message.MediaType.NOTHING;
import static alpha.nomagichttp.message.MediaType.NOTHING_AND_ALL;
import static alpha.nomagichttp.message.MediaType.parse;
import static java.util.List.of;
import static java.util.logging.Level.WARNING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.argThat;

class MediaTypeTest
{
    @Test
    void cache() {
        // Each constant is put in the cache
        assertSame(ALL, parse("*/*"));
        assertSame(TEXT_PLAIN, parse("text/plain"));
        assertSame(TEXT_PLAIN_UTF8, parse("text/plain; charset=utf-8"));
        
        // Plus two specials
        assertSame(parse("text/*"), parse("text/*"));
        assertSame(parse("text/*; charset=utf-8"), parse("text/*; charset=utf-8"));
        
        // For each, we also put a variant without the space
        var noSpace = parse("text/plain;charset=utf-8");
        assertSame(noSpace, parse("text/plain;charset=utf-8"));
        
        // And a charset-quoted variant
        assertSame(parse("text/plain;charset=\"utf-8\""), parse("text/plain;charset=\"utf-8\""));
        assertSame(parse("text/plain; charset=\"utf-8\""), parse("text/plain; charset=\"utf-8\""));
        
        // ..but "UTF-8" any capital letter, no cache
        assertNotSame(parse("text/plain; charset=UTF-8"), parse("text/plain; charset=UTF-8"));
    }
    
    @Test
    void no_params_specific() {
        final String s = "text/plain";
        MediaType actual = parse(s);
        assertThat(actual).isEqualTo(new MediaType(s, "text", "plain", Map.of()));
        assertThat(actual.getClass()).isSameAs(MediaType.class);
        assertThat(actual.toString()).isEqualTo(s);
        assertThat(actual.toStringNormalized()).isEqualTo(s);
    }
    
    @Test
    void no_params_range() {
        final String s = "text/*";
        MediaType actual = parse(s);
        assertThat(actual).isEqualTo(new MediaRange(s, "text", "*", Map.of(), 1));
        assertThat(actual.getClass()).isSameAs(MediaRange.class);
        assertThat(actual.toString()).isEqualTo(s);
        assertThat(actual.toStringNormalized()).isEqualTo("text/*; q=1");
    }
    
    @Test
    void one_param_type() {
        final String s = "text/plain; p=123";
        MediaType actual = parse(s);
        assertThat(actual).isEqualTo(new MediaType(s, "text", "plain", Map.of("p", "123")));
        assertThat(actual.getClass()).isSameAs(MediaType.class);
        assertThat(actual.toString()).isEqualTo(s);
        assertThat(actual.toStringNormalized()).isEqualTo(s);
    }
    
    @Test
    void one_param_range() {
        final String s = "  tExT/ * ; p=  123; q = 0.333";
        MediaType actual = parse(s);
        assertThat(actual).isEqualTo(new MediaRange(s, "text", "*", Map.of("p", "123"), 1 / 3d));
        assertThat(actual.getClass()).isSameAs(MediaRange.class);
        assertThat(actual.toString()).isEqualTo(s);
        assertThat(actual.toStringNormalized()).isEqualTo("text/*; p=123; q=0.333");
    }
    
    @Test
    void two_params_type() {
        final String s = "text/plain; a=123; b=\"x Y z\"";
        MediaType actual = parse(s);
        assertThat(actual).isEqualTo(new MediaType(s, "text", "plain", Map.of("a", "123", "b", "x Y z")));
        assertThat(actual.getClass()).isSameAs(MediaType.class);
        assertThat(actual.toString()).isEqualTo(s);
        // Difference being no quotes
        assertThat(actual.toStringNormalized()).isEqualTo("text/plain; a=123; b=x Y z");
    }
    
    @Test
    void two_params_range() {
        final String s = "text/plain; b=123; a=4 5 6; q=1;";
        MediaType actual = parse(s);
        
        // Different quality, doesn't matter
        assertThat(actual).isEqualTo(new MediaRange(s, "text", "plain", Map.of("a", "4 5 6", "b", "123"), 0.5));
        assertThat(actual.getClass()).isSameAs(MediaRange.class);
        
        assertThat(actual.toString()).isEqualTo(s);
        // Difference is no trailing ';'
        assertThat(actual.toStringNormalized()).isEqualTo("text/plain; b=123; a=4 5 6; q=1");
    }
    
    @Test
    void extension_params() {
        Handler h = Mockito.mock(Handler.class);
        Logging.addHandler(MediaType.class, h);
        
        parse("*/*; q=1; extension=param");
        
        String expMsg = "Media type extension parameters ignored: " +
                Map.of("extension", "param").toString();
        
        Mockito.verify(h).publish(argThat(r ->
                r.getLevel().equals(WARNING) &&
                r.getMessage().equals(expMsg)));
        
        Logging.removeHandler(MediaType.class, h);
    }
    
    @Test
    void q_param_upperCase() {
        MediaRange r = (MediaRange) parse("bla/bla;Q=1.5");
        assertThat(r.quality()).isEqualTo(1.5);
        assertThat(r.toString()).isEqualTo("bla/bla;Q=1.5");
        assertThat(r.toStringNormalized()).isEqualTo("bla/bla; q=1.5");
    }
    
    @Test
    void sentinel_equality() {
        var sentinels = of(ALL, NOTHING, NOTHING_AND_ALL);
        for (MediaType a : sentinels) {
            for (MediaType b : sentinels) {
                if (a == b) {
                    assertEquals(a, b);
                } else {
                    assertNotEquals(a, b);
                }
            }
        }
    }
    
    @Test
    void quoted_once() {
        var mt = parse("text/plain; param=\"val\"");
        assertThat(mt.parameters()).isEqualTo(Map.of("param", "val"));
    }
    
    @Test
    void quoted_escapedQuote() {
        var mt = parse("text/plain; param=\"one \\\"two\\\" three\"");
        assertThat(mt.parameters()).isEqualTo(Map.of("param", "one \"two\" three"));
    }

    @Test
    void quoted_delim() {
        var mt = parse("text/plain; param=\"one;two\"");
        assertThat(mt.parameters()).isEqualTo(Map.of("param", "one;two"));
    }
    
    // TODO: more error cases
}