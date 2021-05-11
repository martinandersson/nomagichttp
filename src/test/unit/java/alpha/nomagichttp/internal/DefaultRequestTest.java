package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.BadHeaderException;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.util.Publishers;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.http.HttpHeaders;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.testutil.Assertions.assertFailed;
import static alpha.nomagichttp.util.Headers.of;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.file.Files.notExists;
import static java.time.Duration.ofDays;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Small tests of {@link DefaultRequest}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class DefaultRequestTest
{
    // Body
    // ----
    
    @Test
    void body_toText_happyPath() {
        Request req = createRequest(of(
                "Content-Length", "3"),
                "abc");
        
        assertThat(req.body().toText()).isCompletedWithValue("abc");
    }
    
    @Test
    void body_toText_empty() {
        assertThat(createEmptyRequest().body().toText()).isCompletedWithValue("");
    }
    
    @Test
    void body_toText_BadHeaderException() {
        Request req = createRequest(of(
                "Content-Length", "3",
                "Content-Type", "first",
                "Content-Type", "second"),
                "abc");
        
        assertFailed(req.body().toText())
                .isExactlyInstanceOf(BadHeaderException.class)
                .hasMessage("Multiple Content-Type values in request.");
    }
    
    @Test
    void body_toText_IllegalCharsetNameException() {
        Request req = createRequest(of(
                "Content-Length", "3",
                "Content-Type", "text/plain;charset=."),
                "abc");
        
        assertFailed(req.body().toText())
                .isExactlyInstanceOf(IllegalCharsetNameException.class);
                // Message not specified
    }
    
    @Test
    void body_toText_UnsupportedCharsetException() {
        Request req = createRequest(of(
                "Content-Length", "3",
                "Content-Type", "text/plain;charset=from-another-galaxy"),
                "abc");
        
        assertFailed(req.body().toText())
                .isExactlyInstanceOf(UnsupportedCharsetException.class);
    }
    
    @Test
    void body_toFile_empty() {
        Path letsHopeItDoesNotExist = Paths.get("list of great child porn sites.txt");
        // Pre condition
        assertThat(notExists(letsHopeItDoesNotExist)).isTrue();
        // Execute
        assertThat(createEmptyRequest().body().toFile(letsHopeItDoesNotExist)).isCompletedWithValue(0L);
        // Post condition (either test failed legitimately or machine is a pedophile)
        assertThat(notExists(letsHopeItDoesNotExist)).isTrue();
    }
    
    // Attributes
    // ----
    
    @Test
    void attributes_set_get() {
        Request r = createEmptyRequest();
        Object o = r.attributes().set("msg", "hello");
        assertThat(o).isNull();
        String n = r.attributes().getAny("msg");
        assertThat(n).isEqualTo("hello");
    }
    
    @Test
    void class_cast_exception_immediate() {
        Request r = createEmptyRequest();
        r.attributes().set("int", 123);
        
        assertThatThrownBy(() -> {
            String crash = r.attributes().getAny("int");
        }).isExactlyInstanceOf(ClassCastException.class);
    }
    
    @Test
    void class_cast_exception_delayed() {
        Request r = createEmptyRequest();
        r.attributes().set("int", 123);
        Optional<String> opt = r.attributes().getOptAny("int");
        
        assertThatThrownBy(() -> {
            String crash = opt.get();
        }).isExactlyInstanceOf(ClassCastException.class);
    }
    
    private static DefaultRequest createRequest(HttpHeaders headers, String body) {
        RequestHead rh = new RequestHead(
                "test-method",
                "test-requestTarget",
                "test-httpVersion",
                headers);
        
        return new DefaultRequest(
                HTTP_1_1,
                rh,
                RequestTarget.parse("/"),
                null,
                Publishers.just(wrap(body, US_ASCII)),
                Mockito.mock(DefaultClientChannel.class),
                null,
                ofDays(99));
    }
    
    private static DefaultRequest createEmptyRequest() {
        return createRequest(of(), "");
    }
    
    private static DefaultPooledByteBufferHolder wrap(String val, Charset charset) {
        ByteBuffer b = ByteBuffer.wrap(val.getBytes(charset));
        return new DefaultPooledByteBufferHolder(b, ignored -> {});
    }
}