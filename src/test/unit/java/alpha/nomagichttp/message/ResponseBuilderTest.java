package alpha.nomagichttp.message;

import org.junit.jupiter.api.Test;

import static alpha.nomagichttp.util.Publishers.empty;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Small tests of {@link Response.Builder}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class ResponseBuilderTest
{
    @Test
    void happy_path() {
        Response r = Response.builder(200, "OK").build();
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.reasonPhrase()).isEqualTo("OK");
        assertThat(r.mustCloseAfterWrite()).isFalse();
        assertSame(r.body(), empty());
    }
    
    @Test
    void future_changes_does_not_affect_old_builders() {
        Response.Builder b0 = Response.builder(1).reasonPhrase(""),
                         b1 = b0.statusCode(2),
                         b2 = b1.statusCode(3);
        
        assertThat(b1.build().statusCode()).isEqualTo(2);
        assertThat(b2.build().statusCode()).isEqualTo(3);
    }
    
    @Test
    void header_addHeader_single() {
        Response r = Response.builder(-1).addHeaders("k", "v").build();
        assertThat(r.headers()).containsExactly("k: v");
    }
    
    @Test
    void header_addHeaders_multi() {
        Response r = Response.builder(-1)
                .header(    "k", "v1")
                .addHeader( "k", "v2")
                .addHeaders("k", "v3",
                            "k", "v4")
                .build();
        
        assertThat(r.headers()).containsExactly(
                "k: v1",
                "k: v2",
                "k: v3",
                "k: v4");
    }
    
    @Test
    void header_removeHeader() {
        Response r = Response.builder(-1).header("k", "v").removeHeader("k").build();
        assertThat(r.headers()).isEmpty();
    }
    
    @Test
    void header_replace() {
        Response r = Response.builder(-1).header("k", "v1").header("k", "v2").build();
        assertThat(r.headers()).containsExactly("k: v2");
    }
    
    @Test
    void informational_response_can_build() {
        Response.builder(102).build();
    }
}