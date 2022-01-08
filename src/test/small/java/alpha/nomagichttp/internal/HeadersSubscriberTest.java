package alpha.nomagichttp.internal;

import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.message.HeaderParseException;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.testutil.ByteBuffers;
import org.assertj.core.api.MapAssert;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletionStage;

import static alpha.nomagichttp.internal.HeadersSubscriber.forRequestHeaders;
import static alpha.nomagichttp.testutil.Assertions.assertFailed;
import static alpha.nomagichttp.testutil.Assertions.assertSucceeded;
import static alpha.nomagichttp.testutil.TestPublishers.map;
import static alpha.nomagichttp.util.Publishers.just;
import static java.util.List.of;
import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Small tests for {@link HeadersSubscriber}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class HeadersSubscriberTest
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
        assertResult(execute(str)).containsOnly(
            entry("User-Agent", of("curl/7.16.3 libcurl/7.16.3 OpenSSL/0.9.7l zlib/1.2.3")),
            entry("Host",       of("www.example.com")),
            entry("Accept",     of("text/plain;charset=utf-8")));
    }
    
    @Test
    void ending_missing() {
        // Each header-field is finished with CRLF, + one CRLF after the section
        // RFC 7230, §3, §4.1.2 and 4.1
        assertFailed(execute("Foo: Bar\n"))
            .isExactlyInstanceOf(AssertionError.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("Unexpected: Channel closed gracefully before parser was done.");
    }
    
    @Test
    void compact() {
        assertResult(execute("hello:world\n\n"))
                .containsOnly(entry("hello", of("world")));
    }
    
    @Test
    void empty_1() {
        assertFailed(execute(""))
            .isExactlyInstanceOf(AssertionError.class)
            .hasNoCause()
            .hasNoSuppressedExceptions()
            .hasMessage("Unexpected: Channel closed gracefully before parser was done.");
    }
    
    @Test
    void empty_2() {
        assertResult(execute("\r\n")).isEmpty();
    }
    
    @Test
    void name_empty() {
        assertFailed(execute(":world\n\n"))
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
        assertFailed(execute("Has Space: blabla..."))
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
        assertFailed(execute("Has\nSpace: blabla..."))
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
        assertFailed(execute("Has-Space : blabla..."))
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
        assertFailed(execute(" Has-Space..."))
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
        assertFailed(execute("""
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
        assertResult(execute("Name:   bla bla   \n\n"))
                .containsOnly(entry("Name", of("bla bla")));
    }
    
    @Test
    void value_isTrimmed_empty() {
        assertResult(execute("Name:      \n\n"))
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
        assertResult(execute(str)).containsOnly(
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
        assertResult(execute(str)).containsOnly(
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
        assertResult(execute(str)).containsOnly(
            // Trailing space on line 1 kept, leading+trailing on line 2 cut
            entry("Name", of("Line:1   Line 2")));
    }
    
    @Test
    void value_folded_orSoParserThought_butHeadersEnded() {
        assertResult(execute("Name:\n   \n\n"))
                .containsOnly(entry("Name", of("")));
    }
    
    private CompletionStage<Request.Headers> execute(String... items) {
        var hs = forRequestHeaders(0, 9_999, mock(ClientChannel.class));
        var up = map(just(items), ByteBuffers::toByteBufferPooled);
        up.subscribe(hs);
        return hs.result();
    }
    
    private MapAssert<String, List<String>> assertResult(
            CompletionStage<Request.Headers> actual) {
        assertSucceeded(actual);
        // lol try to do this with AssertJ's extracting + asInstance...
        return assertThat(actual
                .toCompletableFuture().getNow(null)
                .delegate().map());
    }
}