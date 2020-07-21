package alpha.nomagichttp.message;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
        System.out.println("RAW: " + actual.toString());
        System.out.println("Normal: " + actual.toStringNormalized());
        // Different quality, doesn't matter
        assertThat(actual).isEqualTo(new MediaRange(s, "text", "plain", Map.of("a", "4 5 6", "b", "123"), 0.5));
        assertThat(actual.getClass()).isSameAs(MediaRange.class);
        assertThat(actual.toStringNormalized()).isEqualTo("text/plain; b=123; a=4 5 6; q=1");
    }
    
    // TODO: Fix once we log stuff
    @Test
    void extension_params() {
        MediaType.parse("*/*; q=1; extension=param");
    }
    
    // TODO: more error cases
}
