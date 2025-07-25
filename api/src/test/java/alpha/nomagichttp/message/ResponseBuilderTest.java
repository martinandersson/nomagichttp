package alpha.nomagichttp.message;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.CharacterCodingException;

import static alpha.nomagichttp.HttpConstants.HeaderName.CONNECTION;
import static alpha.nomagichttp.HttpConstants.HeaderName.CONTENT_TYPE;
import static alpha.nomagichttp.testutil.Assertions.assertHeaders;
import static alpha.nomagichttp.util.ByteBufferIterables.empty;
import static alpha.nomagichttp.util.ByteBufferIterables.ofString;
import static alpha.nomagichttp.util.ByteBufferIterables.ofStringUnsafe;
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
    @Nested
    class Building {
        @Test
        void happyPath() {
            Response r = builder(200, "OK").build();
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.reasonPhrase()).isEqualTo("OK");
            assertHeaders(r).isEmpty();
            assertSame(r.body(), empty());
        }
        
        @Test
        void onlyStatusCode() {
            Response r = builder(102).build();
            assertThat(r.statusCode()).isEqualTo(102);
            assertThat(r.reasonPhrase()).isEqualTo("Unknown");
            assertHeaders(r).isEmpty();
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
        
        @ParameterizedTest
        @ValueSource(ints = {123, 204, 304})
        void IllegalResponseBodyExc(int statusCode) throws CharacterCodingException {
            var b = builder(statusCode).body(ofString("Body"));
            assertThatThrownBy(b::build)
                .isExactlyInstanceOf(IllegalResponseBodyException.class)
                    .hasNoCause()
                    .hasNoSuppressedExceptions()
                    .hasMessage("Presumably a body in a $1 (Unknown) response."
                        .replace("$1", Integer.toString(statusCode)));
        }
    }
    
    @Nested
    class Headers {
        @Test
        void addOne() {
            Response r = builder(-1).addHeaders("k", "v").build();
            assertHeaders(r).containsOnly(entry("k", of("v")));
        }
        
        @Test
        void addRepeated() {
            Response r = builder(-1)
                .setHeader( "k", "v1")
                .addHeader( "k", "v2")
                .addHeaders("k", "v3",
                            "k", "v4")
                .build();
            assertHeaders(r).containsOnly(
                entry("k", of("v1", "v2", "v3", "v4")));
        }
        
        // No validation of whitespace in header, values are allowed to be empty
        @Test
        void emptyValues() {
            var r = builder(-1).addHeaders(
                "k 1", "",
                "k 2", "",
                "k 1", "").build();
            assertHeaders(r).containsExactly(
                entry("k 1", of("", "")),
                entry("k 2", of("")));
        }
        
        @Test
        void appendToken_exists() {
            var r = builder(-1).addHeaders(
                        CONNECTION, "upgrade")
                    .appendHeaderToken(CONNECTION, "close")
                    .build();
            assertHeaders(r).containsExactly(
                entry(CONNECTION, of("upgrade, close")));
        }
        
        @Test
        void appendToken_addsNew() {
            var r = builder(-1).addHeaders(
                        "Something", "else")
                    .appendHeaderToken(CONNECTION, "close")
                    .build();
            assertHeaders(r).containsExactly(
                entry("Something", of("else")),
                entry(CONNECTION, of("close")));
        }
        
        @Test
        void appendToken_skipEmpty() {
            var r = builder(-1).addHeaders(
                        CONNECTION, "me-first",
                        CONNECTION, "")
                    .appendHeaderToken(CONNECTION, "close")
                    .build();
            assertHeaders(r).containsExactly(
                entry(CONNECTION, of("me-first, close", "")));
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
        void remove() {
            Response r = builder(-1).setHeader("k", "v").removeHeader("k").build();
            assertHeaders(r).isEmpty();
        }
        
        @Test
        void replace() {
            Response r = builder(-1).setHeader("k", "v1").setHeader("k", "v2").build();
            assertHeaders(r).containsOnly(
                entry("k", of("v2")));
        }
        
        @Test
        void nameIsRepeatedWithDifferentCasing() {
            var b = builder(-1).addHeaders("Abc", "X", "abC", "Boom!");
            assertThatThrownBy(b::build)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(
                    // Caused by
                    "java.lang.IllegalArgumentException: Header name repeated with different casing: abC")
                .hasNoSuppressedExceptions();
        }
        
        @Test
        void nameIsEmpty() {
            var b = builder(-1);
            assertThatThrownBy(() -> b.setHeader("", "<-- empty"))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Empty header name")
                .hasNoSuppressedExceptions()
                .hasNoCause();
        }
        
        @Test
        void connectionCloseOn1XX() {
            var b = builder(123).setHeader("coNnEcTiOn", "cLoSe");
            assertThatThrownBy(b::build)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(
                    "\"Connection: close\" set on 1XX (Informational) response.")
                .hasNoCause()
                .hasNoSuppressedExceptions();
        }
    }
    
    @Nested
    class Body {
        @Test
        void canBuildWithBodyButNoContentType() {
            var rsp = builder(-1).body(ofStringUnsafe("Blah")).build();
            assertThat(rsp.headers().contentType()).isEmpty();
        }
        
        @Test
        void removeNonEmpty() {
            var rsp = builder(-1)
                .setHeader(CONTENT_TYPE, "application/blah")
                .body(ofStringUnsafe("Blah"))
                // Instant regrets
                .body(empty())
                .build();
            // Header removed!
            assertThat(rsp.headers().contentType()).isEmpty();
        }
    }
    
    // Response.builder() uses a cache, as this is a test we prefer to bypass it
    private static Response.Builder builder(int code) {
        return DefaultResponse.DefaultBuilder.ROOT
                 .statusCode(code);
    }
    
    private static Response.Builder builder(int code, String phrase) {
        return DefaultResponse.DefaultBuilder.ROOT
                 .statusCode(code)
                 .reasonPhrase(phrase);
    }
}
