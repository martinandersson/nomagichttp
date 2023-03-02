package alpha.nomagichttp.message;

import org.junit.jupiter.api.Test;

import static alpha.nomagichttp.util.ByteBufferIterables.empty;
import static java.util.List.of;
import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Small tests of {@link Response.Builder}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ResponseBuilderTest
{
    @Test
    void happyPath() {
        Response r = builder(200, "OK").build();
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.reasonPhrase()).isEqualTo("OK");
        assertThat(r.headers().delegate().map()).isEmpty();
        assertSame(r.body(), empty());
    }
    
    @Test
    void onlyStatusCode() {
        Response r = builder(102).build();
        assertThat(r.statusCode()).isEqualTo(102);
        assertThat(r.reasonPhrase()).isEqualTo("Unknown");
        assertThat(r.headers().delegate().map()).isEmpty();
        assertSame(r.body(), empty());
    }
    
    @Test
    void changesAffectNewInstanceNotOld() {
        Response.Builder b1 = builder(1).reasonPhrase(""),
                         b2 = b1.statusCode(2),
                         b3 = b2.statusCode(3);
        assertThat(b1.build().statusCode()).isEqualTo(1);
        assertThat(b2.build().statusCode()).isEqualTo(2);
        assertThat(b3.build().statusCode()).isEqualTo(3);
    }
    
    @Test
    void headerAddOne() {
        Response r = builder(-1).addHeaders("k", "v").build();
        assertThat(r.headersForWriting()).containsExactly("k: v");
    }
    
    @Test
    void headersAddRepeated() {
        Response r = builder(-1)
            .header(    "k", "v2")
            .addHeader( "k", "v1")
            .addHeaders("k", "v3",
                        "k", "v2")
            .build();
        
        assertThat(r.headers().delegate().map()).containsOnly(
            entry("k", of("v2", "v1", "v3", "v2")));
        
        assertThat(r.headersForWriting()).containsExactly(
            "k: v2",
            "k: v1",
            "k: v3",
            "k: v2");
    }
    
    @Test
    void headerEmptyValue() {
        var r = builder(-1).addHeaders(
            "Key 1", "",
            "Key 2", "   ").build();
        assertThat(r.headersForWriting()).containsExactly(
            "Key 1: ",
            "Key 2:    ");
    }
    
    @Test
    void headerRemove() {
        Response r = builder(-1).header("k", "v").removeHeader("k").build();
        assertThat(r.headersForWriting()).isEmpty();
    }
    
    @Test
    void headerReplace() {
        Response r = builder(-1).header("k", "v1").header("k", "v2").build();
        assertThat(r.headersForWriting()).containsExactly("k: v2");
    }
    
    @Test
    void headerNameIsRepeatedWithDifferentCasing() {
        var b = builder(-1).addHeaders("Abc", "X", "abC", "Boom!");
        assertThatThrownBy(b::build)
            .isExactlyInstanceOf(IllegalStateException.class)
            .hasMessage(
                "java.lang.IllegalArgumentException: duplicate key: abC")
            .hasNoSuppressedExceptions()
            .cause()
                // From HttpHeaders.of
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("duplicate key: abC")
                .hasNoSuppressedExceptions()
                .hasNoCause();
    }
    
    @Test
    void headerNameIsEmpty() {
        var b = builder(-1).header("  ", "<-- empty");
        assertThatThrownBy(b::build)
            .isExactlyInstanceOf(IllegalStateException.class)
            .hasMessage(
                "java.lang.IllegalArgumentException: empty key")
            .hasNoSuppressedExceptions()
            .cause()
                // From HttpHeaders.of
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasNoSuppressedExceptions()
                .hasNoCause()
                .hasMessage("empty key");
    }
    
    @Test
    void connectionCloseOn1XX() {
        var b = builder(123).header("coNnEcTiOn", "cLoSe");
        assertThatThrownBy(b::build)
            .isExactlyInstanceOf(IllegalStateException.class)
            .hasMessage(
                "\"Connection: close\" set on 1XX (Informational) response.")
            .hasNoCause()
            .hasNoSuppressedExceptions();
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