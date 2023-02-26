package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.HeaderParseException;
import alpha.nomagichttp.message.Request;
import org.assertj.core.api.MapAssert;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static alpha.nomagichttp.internal.ParserOf.headers;
import static alpha.nomagichttp.testutil.TestByteBufferIterables.just;
import static java.util.List.of;
import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Small tests of {@link ParserOf}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class ParserOfTest
{
    @Test
    void ending_mixed() {
        // Three line endings; \r\n, \n, \n\r\n
        // (\r ignored)
        var str = """
            User-Agent: curl/7.16.3 libcurl/7.16.3 OpenSSL/0.9.7l zlib/1.2.3\r
            Host: www.example.com
            Accept: text/plain;charset=utf-8\n\r
            """;
        assertThat(parse(str)).containsOnly(
            entry("User-Agent", of("curl/7.16.3 libcurl/7.16.3 OpenSSL/0.9.7l zlib/1.2.3")),
            entry("Host",       of("www.example.com")),
            entry("Accept",     of("text/plain;charset=utf-8")));
    }
    
    @Test
    void ending_missing() {
        // Each header-field is finished with CRLF, + one CRLF after the section
        // RFC 7230, §3, §4.1.2 and 4.1
        assertThatThrownBy(() -> parse("Foo: Bar\n"))
            .isExactlyInstanceOf(AssertionError.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("Unexpected: Channel closed gracefully before parser was done.");
    }
    
    @Test
    void compact() {
        assertThat(parse("hello:world\n\n"))
                .containsOnly(entry("hello", of("world")));
    }
    
    @Test
    void empty_1() {
        assertThatThrownBy(() -> parse(""))
            .isExactlyInstanceOf(AssertionError.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("Unexpected: Channel closed gracefully before parser was done.");
    }
    
    @Test
    void empty_2() {
        assertThat(parse("\r\n")).isEmpty();
    }
    
    @Test
    void name_empty() {
        assertThatThrownBy(() -> parse(":world\n\n"))
            .isExactlyInstanceOf(HeaderParseException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasToString("""
                HeaderParseException{\
                prev=N/A, \
                curr=(hex:0x3A, decimal:58, char:":"), \
                pos=0, \
                msg=Empty header name.}""");
    }
    
    @Test
    void name_spaceInName_sp() {
        assertThatThrownBy(() -> parse("Has Space: blabla..."))
            .isExactlyInstanceOf(HeaderParseException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasToString("""
                HeaderParseException{\
                prev=(hex:0x73, decimal:115, char:"s"), \
                curr=(hex:0x20, decimal:32, char:" "), \
                pos=3, \
                msg=Whitespace in header name or before colon is not accepted.}""");
    }
    
    @Test
    void name_spaceInName_LF() {
        assertThatThrownBy(() -> parse("Has\nSpace: blabla..."))
            .isExactlyInstanceOf(HeaderParseException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasToString("""
                HeaderParseException{\
                prev=(hex:0x73, decimal:115, char:"s"), \
                curr=(hex:0xA, decimal:10, char:"\\n"), \
                pos=3, \
                msg=Whitespace in header name or before colon is not accepted.}""");
    }
    
    @Test
    void name_spaceAfterName() {
        assertThatThrownBy(() -> parse("Has-Space : blabla..."))
            .isExactlyInstanceOf(HeaderParseException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasToString("""
                HeaderParseException{\
                prev=(hex:0x65, decimal:101, char:"e"), \
                curr=(hex:0x20, decimal:32, char:" "), \
                pos=9, \
                msg=Whitespace in header name or before colon is not accepted.}""");
    }
    
    @Test
    void name_spaceBeforeName() {
        assertThatThrownBy(() -> parse(" Has-Space..."))
            .isExactlyInstanceOf(HeaderParseException.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasToString("""
                HeaderParseException{\
                prev=N/A, \
                curr=(hex:0x20, decimal:32, char:" "), \
                pos=0, \
                msg=Leading whitespace in header name is not accepted.}""");
    }
    
    @Test
    void name_duplicate() {
        assertThatThrownBy(() -> parse("""
                foo: bar
                FOO: bar\n
                """))
            .isExactlyInstanceOf(HeaderParseException.class)
            .hasToString("HeaderParseException{prev=N/A, curr=N/A, pos=N/A, msg=null}")
            // "duplicate key: foo"
            .hasCauseExactlyInstanceOf(IllegalArgumentException.class);
    }
    
    @Test
    void value_isTrimmed_content() {
        assertThat(parse("Name:   bla bla   \n\n"))
                .containsOnly(entry("Name", of("bla bla")));
    }
    
    @Test
    void value_isTrimmed_empty() {
        assertThat(parse("Name:      \n\n"))
                .containsOnly(entry("Name", of("")));
    }
    
    @Test
    void value_empty_and_name_repeated() {
        var str = """
            Foo:
            Bar: hello
            Foo: world
            Foo: again\n
            """;
        assertThat(parse(str)).containsOnly(
            entry("Foo", of("", "world", "again")),
            entry("Bar", of("hello")));
    }
    
    @Test
    void value_folded_afterContent() {
        var str = """
            Name: Line 1
              Line 2
            Another: Value\n
            """;
        assertThat(parse(str)).containsOnly(
            entry("Name",    of("Line 1 Line 2")),
            entry("Another", of("Value")));
    }
    
    // Empty values are valid, but here "Line 1" is considered a folded value.
    @Test
    void value_folded_immediately() {
        var str = """
            Name:\s\s\s
             Line:1\s\s\s
               Line 2\s\s\s\n
            """;
        assertThat(parse(str)).containsOnly(
            // Trailing space on line 1 kept, leading+trailing on line 2 cut
            entry("Name", of("Line:1   Line 2")));
    }
    
    @Test
    void value_folded_orSoParserThought_butHeadersEnded() {
        assertThat(parse("Name:\n   \n\n"))
                .containsOnly(entry("Name", of("")));
    }
    
    private Request.Headers parse(String... items) {
        try {
            return headers(just(items), 0, 9_999).parse();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
    
    private static MapAssert<String, List<String>> assertThat(Request.Headers actual) {
        return org.assertj.core.api.Assertions.assertThat(actual.delegate().map());
    }
}