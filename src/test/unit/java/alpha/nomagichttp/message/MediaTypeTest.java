package alpha.nomagichttp.message;

import alpha.nomagichttp.testutil.Logging;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.logging.Handler;

import static java.util.logging.Level.WARNING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;

class MediaTypeTest
{
    @Test
    void no_params_specific() {
        String s = "text/plain";
        MediaType actual = MediaType.parse(s);
        assertThat(actual).isEqualTo(new MediaType(s, "text", "plain", Map.of()));
        assertThat(actual.getClass()).isSameAs(MediaType.class);
        assertThat(actual.toStringNormalized()).isEqualTo("text/plain");
    }
    
    @Test
    void no_params_range() {
        String s = "text/*";
        MediaType actual = MediaType.parse(s);
        assertThat(actual).isEqualTo(new MediaRange(s, "text", "*", Map.of(), 1));
        assertThat(actual.getClass()).isSameAs(MediaRange.class);
        assertThat(actual.toStringNormalized()).isEqualTo("text/*; q=1");
    }
    
    @Test
    void one_param_type() {
        String s = "text/plain; p=123";
        MediaType actual = MediaType.parse(s);
        assertThat(actual).isEqualTo(new MediaType(s, "text", "plain", Map.of("p", "123")));
        assertThat(actual.getClass()).isSameAs(MediaType.class);
        assertThat(actual.toStringNormalized()).isEqualTo("text/plain; p=123");
    }
    
    @Test
    void one_param_range() {
        String s = "  tExT/ * ; p=  123; q = 0.333";
        MediaType actual = MediaType.parse(s);
        assertThat(actual).isEqualTo(new MediaRange(s, "text", "*", Map.of("p", "123"), 1 / 3d));
        assertThat(actual.getClass()).isSameAs(MediaRange.class);
        assertThat(actual.toStringNormalized()).isEqualTo("text/*; p=123; q=0.333");
    }
    
    @Test
    void two_params_type() {
        String s = "text/plain; a=123; b=\"x Y z\"";
        MediaType actual = MediaType.parse(s);
        assertThat(actual).isEqualTo(new MediaType(s, "text", "plain", Map.of("a", "123", "b", "x Y z")));
        assertThat(actual.getClass()).isSameAs(MediaType.class);
        assertThat(actual.toStringNormalized()).isEqualTo("text/plain; a=123; b=x Y z");
    }
    
    @Test
    void two_params_range() {
        String s = "text/plain; b=123; a=4 5 6; q=1;";
        MediaType actual = MediaType.parse(s);
        System.out.println("Raw: " + actual.toString());
        System.out.println("Normal: " + actual.toStringNormalized());
        // Different quality, doesn't matter
        assertThat(actual).isEqualTo(new MediaRange(s, "text", "plain", Map.of("a", "4 5 6", "b", "123"), 0.5));
        assertThat(actual.getClass()).isSameAs(MediaRange.class);
        assertThat(actual.toStringNormalized()).isEqualTo("text/plain; b=123; a=4 5 6; q=1");
    }
    
    @Test
    void extension_params() {
        Handler handler = Mockito.mock(Handler.class);
        Logging.addHandler(MediaType.class, handler);
        
        MediaType.parse("*/*; q=1; extension=param");
        
        String expMsg = "Media type extension parameters ignored: " +
                Map.of("extension", "param").toString();
        
        Mockito.verify(handler).publish(argThat(r ->
                r.getLevel().equals(WARNING) &&
                r.getMessage().equals(expMsg)));
    }
    
    // TODO: more error cases
}
