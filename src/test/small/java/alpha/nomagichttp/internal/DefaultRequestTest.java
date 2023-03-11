package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.BadHeaderException;
import alpha.nomagichttp.message.RawRequest;
import alpha.nomagichttp.message.Request;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpHeaders;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Paths;
import java.util.concurrent.ExecutionException;

import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.internal.DefaultRequest.requestWithoutParams;
import static alpha.nomagichttp.internal.SkeletonRequestTarget.parse;
import static alpha.nomagichttp.testutil.ReadableByteChannels.ofString;
import static alpha.nomagichttp.util.Headers.of;
import static java.nio.file.Files.notExists;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Small tests of {@link DefaultRequest}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class DefaultRequestTest
{
    @Test
    void body_toText_happyPath() throws ExecutionException, InterruptedException {
        var req = createRequest(of("Content-Length", "3"), "abc");
        // Otherwise WrongThreadException
        final String str;
        try (var vThread = newVirtualThreadPerTaskExecutor()) {
            str = vThread.submit(() -> req.body().toText()).get();
        }
        assertThat(str).isEqualTo("abc");
    }
    
    @Test
    void body_toText_empty() throws IOException {
        assertThat(createEmptyRequest().body().toText()).isEmpty();
    }
    
    @Test
    void body_toText_BadHeaderException() {
        var req = createRequest(of(
                "Content-Length", "3",
                "Content-Type", "first",
                "Content-Type", "second"),
                "abc");
        assertThatThrownBy(() -> req.body().toText())
                .isExactlyInstanceOf(BadHeaderException.class)
                .hasMessage("Multiple Content-Type values in request.");
    }
    
    @Test
    void body_toText_IllegalCharsetNameException() {
        var req = createRequest(of(
                "Content-Length", "3",
                "Content-Type", "text/plain;charset=."),
                "abc");
        assertThatThrownBy(() -> req.body().toText())
                .isExactlyInstanceOf(IllegalCharsetNameException.class);
                // Message not specified
    }
    
    @Test
    void body_toText_UnsupportedCharsetException() {
        var req = createRequest(of(
                "Content-Length", "3",
                "Content-Type", "text/plain;charset=from-another-galaxy"),
                "abc");
        assertThatThrownBy(() -> req.body().toText())
                .isExactlyInstanceOf(UnsupportedCharsetException.class);
    }
    
    @Test
    void body_toFile_empty() throws IOException {
        var letsHopeItDoesNotExist = Paths.get("child porn sites.txt");
        // Pre condition
        assertThat(notExists(letsHopeItDoesNotExist))
                .isTrue();
        // Execute
        assertThat(createEmptyRequest().body().toFile(letsHopeItDoesNotExist))
                .isZero();
        // Post condition (test failed legitimately, or machine is a pedophile?)
        assertThat(notExists(letsHopeItDoesNotExist))
                .isTrue();
    }
    
    private static Request createRequest(HttpHeaders headers, String reqBody) {
        var line = new RawRequest.Line(
                       "test-method",
                       "test-requestTarget",
                       "test-httpVersion",
                       -1, -1);
        var head = new RawRequest.Head(
                       line,
                       new RequestHeaders(headers));
        var body = RequestBody.of(
                       head.headers(),
                       new ChannelReader(ofString(reqBody)));
        var skel = new SkeletonRequest(
                       head,
                       HTTP_1_1,
                       parse("/?"),
                       body,
                       new DefaultAttributes());
        return requestWithoutParams(null, skel);
    }
    
    private static Request createEmptyRequest() {
        return createRequest(of(), "");
    }
}