package alpha.nomagichttp.message;

import org.assertj.core.api.MapAssert;
import org.junit.jupiter.api.Test;

import java.util.List;

import static alpha.nomagichttp.testutil.Headers.linkedHashMap;
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
        assertHeadersMap(r).isEmpty();
        assertSame(r.body(), empty());
    }
    
    @Test
    void onlyStatusCode() {
        Response r = builder(102).build();
        assertThat(r.statusCode()).isEqualTo(102);
        assertThat(r.reasonPhrase()).isEqualTo("Unknown");
        assertHeadersMap(r).isEmpty();
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
        assertHeadersMap(r).containsOnly(entry("k", of("v")));
    }
    
    @Test
    void headersAddRepeated() {
        Response r = builder(-1)
            .header(    "k", "v2")
            .addHeader( "k", "v1")
            .addHeaders("k", "v3",
                        "k", "v2")
            .build();
        assertHeadersMap(r).containsOnly(
            entry("k", of("v2", "v1", "v3", "v2")));
    }
    
    // TODO: Add Key X " " (one whitespace) below
    //       Also need tests white whitespace in the name.
    // RFC 7230 §3.2.5. Field Parsing
    // "The field value does not
    //   include any leading or trailing whitespace" so we should actually trim the values.
    // Might as well trim the header names too. Will simplify API; one less
    // "IllegalStateException" upon building
    // TODO: equals; order does not matter
    
    
    // No validation of whitespace in header, values are allowed to be empty
    @Test
    void headerEmptyValues() {
        var r = builder(-1).addHeaders(
            "k 1", "",
            "k 2", "",
            "k 1", "").build();
        assertHeadersMap(r).containsExactly(
            entry("k 1", of("", "")),
            entry("k 2", of("")));
    }
    
    @Test
    void noLeadingWhiteSpace_key() {
        assertWhiteSpaceCrash(" k", "v", "Leading", " k");
    }
    
    @Test
    void noLeadingWhiteSpace_value() {
        assertWhiteSpaceCrash("k", " v", "Leading", " v");
    }
    
    @Test
    void noTrailingWhiteSpace_key() {
        assertWhiteSpaceCrash("k ", "v", "Trailing", "k ");
    }
    
    @Test
    void noTrailingWhiteSpace_value() {
        assertWhiteSpaceCrash("k", "v ", "Trailing", "v ");
    }
    
    private static void assertWhiteSpaceCrash(
            String k, String v, String prefix, String bad) {
        var b = builder(-1);
        assertThatThrownBy(() -> b.addHeader(k, v))
            .isExactlyInstanceOf(IllegalArgumentException.class)
            .hasMessage(prefix + " whitespace in \"" + bad + "\".")
            .hasNoCause()
            .hasNoSuppressedExceptions();
    }
    
    @Test
    void headerRemove() {
        Response r = builder(-1).header("k", "v").removeHeader("k").build();
        assertHeadersMap(r).isEmpty();
    }
    
    @Test
    void headerReplace() {
        Response r = builder(-1).header("k", "v1").header("k", "v2").build();
        assertHeadersMap(r).containsOnly(
            entry("k", of("v2")));
    }
    
    @Test
    void headerNameIsRepeatedWithDifferentCasing() {
        var b = builder(-1).addHeaders("Abc", "X", "abC", "Boom!");
        assertThatThrownBy(b::build)
            .isExactlyInstanceOf(IllegalStateException.class)
            .hasMessage(
                // Caused by
                "java.lang.IllegalArgumentException: Header name repeated with different casing: abC")
            .hasNoSuppressedExceptions();
    }
    
    @Test
    void headerNameIsEmpty() {
        var b = builder(-1);
        assertThatThrownBy(() -> b.header("", "<-- empty"))
            .isExactlyInstanceOf(IllegalArgumentException.class)
            .hasMessage("Empty header name")
            .hasNoSuppressedExceptions()
            .hasNoCause();
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
    
    private static MapAssert<String, List<String>> assertHeadersMap(Response r) {
        return assertThat(linkedHashMap(r.headers()));
    }
}