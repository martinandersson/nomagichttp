package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.BadHeaderException;
import alpha.nomagichttp.message.DefaultContentHeaders;
import alpha.nomagichttp.message.RawRequest;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.util.Publishers;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.http.HttpHeaders;
import java.nio.ByteBuffer;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Paths;
import java.util.List;

import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.testutil.Assertions.assertFailed;
import static alpha.nomagichttp.util.Headers.of;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.file.Files.notExists;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Small tests of {@link DefaultRequest}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class DefaultRequestTest
{
    @Test
    void body_toText_happyPath() {
        var req = createRequest(of("Content-Length", "3"),"abc");
        assertThat(req.body().toText())
                .isCompletedWithValue("abc");
    }
    
    @Test
    void body_toText_empty() {
        assertThat(createEmptyRequest().body().toText())
                .isCompletedWithValue("");
    }
    
    @Test
    void body_toText_BadHeaderException() {
        var req = createRequest(of(
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
        var req = createRequest(of(
                "Content-Length", "3",
                "Content-Type", "text/plain;charset=."),
                "abc");
        assertFailed(req.body().toText())
                .isExactlyInstanceOf(IllegalCharsetNameException.class);
                // Message not specified
    }
    
    @Test
    void body_toText_UnsupportedCharsetException() {
        var req = createRequest(of(
                "Content-Length", "3",
                "Content-Type", "text/plain;charset=from-another-galaxy"),
                "abc");
        assertFailed(req.body().toText())
                .isExactlyInstanceOf(UnsupportedCharsetException.class);
    }
    
    @Test
    void body_toFile_empty() {
        var letsHopeItDoesNotExist = Paths.get("list of great child porn sites.txt");
        // Pre condition
        assertThat(notExists(letsHopeItDoesNotExist))
                .isTrue();
        // Execute
        assertThat(createEmptyRequest().body().toFile(letsHopeItDoesNotExist))
                .isCompletedWithValue(0L);
        // Post condition (either test failed legitimately or machine is a pedophile)
        assertThat(notExists(letsHopeItDoesNotExist))
                .isTrue();
    }
    
    private static Request createRequest(HttpHeaders headers, String reqBody) {
        var line = new RawRequest.Line(
                       "test-method", "test-requestTarget", "test-httpVersion", -1, -1);
        var head = new RawRequest.Head(line, new RequestHeaders(headers));
        var body = RequestBody.of(
                  (DefaultContentHeaders) head.headers(),
                  Publishers.just(wrap(reqBody)),
                  Mockito.mock(DefaultClientChannel.class),
                  null, null);
        
        SkeletonRequest r = new SkeletonRequest(
                head, SkeletonRequestTarget.parse("/?"), body, new DefaultAttributes());
        
        return new DefaultRequest(HTTP_1_1, r, List.of());
    }
    
    private static Request createEmptyRequest() {
        return createRequest(of(), "");
    }
    
    private static DefaultPooledByteBufferHolder wrap(String val) {
        ByteBuffer b = ByteBuffer.wrap(val.getBytes(US_ASCII));
        return new DefaultPooledByteBufferHolder(b, ignored -> {});
    }
}