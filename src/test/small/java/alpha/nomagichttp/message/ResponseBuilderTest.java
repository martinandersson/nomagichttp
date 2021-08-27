package alpha.nomagichttp.message;

import org.junit.jupiter.api.Test;

import static alpha.nomagichttp.util.Publishers.empty;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        Response r = builder(200, "OK").build();
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.reasonPhrase()).isEqualTo("OK");
        assertThat(r.mustCloseAfterWrite()).isFalse();
        assertSame(r.body(), empty());
    }
    
    @Test
    void future_changes_does_not_affect_old_builders() {
        Response.Builder b0 = builder(1).reasonPhrase(""),
                         b1 = b0.statusCode(2),
                         b2 = b1.statusCode(3);
        
        assertThat(b1.build().statusCode()).isEqualTo(2);
        assertThat(b2.build().statusCode()).isEqualTo(3);
    }
    
    @Test
    void header_addHeader_single() {
        Response r = builder(-1).addHeaders("k", "v").build();
        assertThat(r.headersForWriting()).containsExactly("k: v");
    }
    
    @Test
    void header_addHeaders_multi() {
        Response r = builder(-1)
                .header(    "k", "v1")
                .addHeader( "k", "v2")
                .addHeaders("k", "v3",
                            "k", "v4")
                .build();
        
        assertThat(r.headersForWriting()).containsExactly(
                "k: v1",
                "k: v2",
                "k: v3",
                "k: v4");
    }
    
    @Test
    void header_removeHeader() {
        Response r = builder(-1).header("k", "v").removeHeader("k").build();
        assertThat(r.headersForWriting()).isEmpty();
    }
    
    @Test
    void header_replace() {
        Response r = builder(-1).header("k", "v1").header("k", "v2").build();
        assertThat(r.headersForWriting()).containsExactly("k: v2");
    }
    
    @Test
    void informational_response_can_build() {
        builder(102).build();
    }
    
    @Test
    void multipleContentLength_withValues() {
        Response.Builder b = builder(123).addHeaders(
                "Content-Length", "1",
                "Content-Length", "2");
        
        assertThatThrownBy(b::build)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Multiple Content-Length headers.");
    }
    
    @Test
    void multipleContentLength_noValues() {
        Response.Builder b = builder(123).addHeaders(
                "Content-Length", "",
                "Content-Length", "    ");
        
        assertThatThrownBy(b::build)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Multiple Content-Length headers.");
    }
    
    @Test
    void connectionCloseOn1XX() {
        Response.Builder b = builder(123).header("coNnEcTiOn", "cLoSe");
        
        assertThatThrownBy(b::build)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("\"Connection: close\" set on 1XX (Informational) response.");
    }
    
    // Response.builder() uses a cache, as this is a test we prefer to bypass it
    private static Response.Builder builder(int code) {
        return DefaultResponse.DefaultBuilder.ROOT
                .statusCode(code);
    }
    
    private static Response.Builder builder(int code, String phrase) {
        return DefaultResponse.DefaultBuilder.ROOT.
                statusCode(code)
                .reasonPhrase(phrase);
    }
}