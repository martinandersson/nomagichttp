package alpha.nomagichttp.core;

import alpha.nomagichttp.message.HeaderParseException;
import alpha.nomagichttp.message.Request;
import org.assertj.core.api.AbstractThrowableAssert;
import org.junit.jupiter.api.Test;

import static alpha.nomagichttp.core.ParserOf.headers;
import static alpha.nomagichttp.testutil.Assertions.assertHeaders;
import static alpha.nomagichttp.testutil.ByteBufferIterables.just;
import static alpha.nomagichttp.util.Blah.throwsNoChecked;
import static java.util.List.of;
import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Small tests of {@link ParserOf}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class ParserOfTest
{
    @Test
    void ending_mixed() {
        // Three different line endings; \r\n, \n, \n\r\n
        // (\r ignored)
        var str = """
            User-Agent: curl/7.16.3 libcurl/7.16.3 OpenSSL/0.9.7l zlib/1.2.3\r
            Host: www.example.com
            Accept: text/plain;charset=utf-8\n\r
            """;
        assertHeaders(parse(str)).containsExactly(
            entry("User-Agent", of("curl/7.16.3 libcurl/7.16.3 OpenSSL/0.9.7l zlib/1.2.3")),
            entry("Host",       of("www.example.com")),
            entry("Accept",     of("text/plain;charset=utf-8")));
    }
    
    @Test
    void ending_missing() {
        // Each header-field is finished with CRLF, + one CRLF after the section
        // (RFC 7230, §3, §4.1.2 and 4.1)
        assertThrowsParseExc("Foo: Bar\n") // <-- only one LF
            .hasToString("""
                HeaderParseException{\
                prev=(hex:0xA, decimal:10, char:"\\n"), \
                curr=N/A, \
                pos=8, \
                msg=Upstream finished prematurely.}""");
    }
    
    @Test
    void compact() {
        assertHeaders(parse("hello:world\n\n"))
            .containsOnly(entry("hello", of("world")));
    }
    
    @Test
    void empty_1() {
        assertThrowsParseExc("")
            .hasToString("""
                HeaderParseException{\
                prev=N/A, \
                curr=N/A, \
                pos=0, \
                msg=Upstream finished prematurely.}""");
    }
    
    @Test
    void empty_2() {
        assertHeaders(parse("\r\n")).isEmpty();
    }
    
    @Test
    void name_empty() {
        assertThrowsParseExc(":world\n\n")
            .hasToString("""
                HeaderParseException{\
                prev=N/A, \
                curr=(hex:0x3A, decimal:58, char:":"), \
                pos=0, \
                msg=Empty header name.}""");
    }
    
    @Test
    void name_spaceInName_sp() {
        assertThrowsParseExc("Has Space: blabla...")
            .hasToString("""
                HeaderParseException{\
                prev=(hex:0x73, decimal:115, char:"s"), \
                curr=(hex:0x20, decimal:32, char:" "), \
                pos=3, \
                msg=Whitespace in header name or before colon is not accepted.}""");
    }
    
    @Test
    void name_spaceInName_LF() {
        assertThrowsParseExc("Has\nSpace: blabla...")
            .hasToString("""
                HeaderParseException{\
                prev=(hex:0x73, decimal:115, char:"s"), \
                curr=(hex:0xA, decimal:10, char:"\\n"), \
                pos=3, \
                msg=Whitespace in header name or before colon is not accepted.}""");
    }
    
    @Test
    void name_spaceAfterName() {
        assertThrowsParseExc("Has-Space : blabla...")
            .hasToString("""
                HeaderParseException{\
                prev=(hex:0x65, decimal:101, char:"e"), \
                curr=(hex:0x20, decimal:32, char:" "), \
                pos=9, \
                msg=Whitespace in header name or before colon is not accepted.}""");
    }
    
    @Test
    void name_spaceBeforeName() {
        assertThrowsParseExc(" Has-Space...")
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
            .hasCauseExactlyInstanceOf(IllegalArgumentException.class)
            .hasNoSuppressedExceptions();
    }
    
    @Test
    void value_isTrimmed_content() {
        assertHeaders(parse("Name:   bla bla   \n\n"))
                .containsOnly(entry("Name", of("bla bla")));
    }
    
    @Test
    void value_isTrimmed_empty() {
        assertHeaders(parse("Name:      \n\n"))
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
        assertHeaders(parse(str)).containsExactly(
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
        assertHeaders(parse(str)).containsExactly(
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
        assertHeaders(parse(str)).containsOnly(
            // Trailing space on line 1 kept, leading+trailing on line 2 cut
            entry("Name", of("Line:1   Line 2")));
    }
    
    @Test
    void value_folded_orSoParserThought_butHeadersEnded() {
        assertHeaders(parse("Name:\n   \n\n"))
                .containsOnly(entry("Name", of("")));
    }
    
    private static AbstractThrowableAssert<?, ? extends Throwable>
        assertThrowsParseExc(String str) {
            return assertThatThrownBy(() -> parse(str))
                    .isExactlyInstanceOf(HeaderParseException.class)
                    .hasNoCause()
                    .hasNoSuppressedExceptions();
    }
    
    private static Request.Headers parse(String... items) {
        var parser = headers(just(items), 0, 9_999);
        // Throws no IOException because we are not reading from a channel
        return throwsNoChecked(parser::parse);
    }
}