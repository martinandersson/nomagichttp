package alpha.nomagichttp.core;

import alpha.nomagichttp.message.BadHeaderException;
import alpha.nomagichttp.message.RawRequest;
import alpha.nomagichttp.message.Request;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Paths;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.core.DefaultRequest.requestWithoutParams;
import static alpha.nomagichttp.core.SkeletonRequestTarget.parse;
import static alpha.nomagichttp.testutil.Headers.linkedHashMap;
import static alpha.nomagichttp.testutil.ReadableByteChannels.ofString;
import static alpha.nomagichttp.testutil.ScopedValues.whereServerIsBound;
import static alpha.nomagichttp.testutil.VThreads.getUsingVThread;
import static java.nio.file.Files.notExists;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Small tests of {@link DefaultRequest}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class DefaultRequestTest
{
    @Test
    void body_toText_happyPath() throws Exception {
        var req = createRequest("abc", "Content-Length", "3");
        // Implementation needs access to Config.maxRequestBodyBufferSize()
        final String str = whereServerIsBound(() ->
            // ...and a virtual thread, otherwise WrongThreadException
            getUsingVThread(() -> req.body().toText()));
        assertThat(str).isEqualTo("abc");
    }
    
    @Test
    void body_toText_empty() throws IOException {
        assertThat(createEmptyRequest().body().toText()).isEmpty();
    }
    
    @Test
    void body_toText_BadHeaderException() {
        var req = createRequest("abc",
                "Content-Length", "3",
                "Content-Type", "first",
                "Content-Type", "second");
        assertThatThrownBy(() -> req.body().toText())
                .isExactlyInstanceOf(BadHeaderException.class)
                .hasMessage("Multiple Content-Type values in request.");
    }
    
    @Test
    void body_toText_IllegalCharsetNameException() {
        var req = createRequest("abc",
                "Content-Length", "3",
                "Content-Type", "text/plain;charset=.");
        assertThatThrownBy(() -> req.body().toText())
                .isExactlyInstanceOf(IllegalCharsetNameException.class);
                // Message not specified
    }
    
    @Test
    void body_toText_UnsupportedCharsetException() {
        var req = createRequest("abc",
                "Content-Length", "3",
                "Content-Type", "text/plain;charset=from-another-galaxy");
        assertThatThrownBy(() -> req.body().toText())
                .isExactlyInstanceOf(UnsupportedCharsetException.class);
    }
    
    @Test
    void body_toFile_empty()
            throws InterruptedException, ExecutionException, TimeoutException {
        var letsHopeItDoesNotExist = Paths.get("child porn sites.txt");
        // Pre condition
        assertThat(notExists(letsHopeItDoesNotExist))
                .isTrue();
        // Execute
        var body = createEmptyRequest().body();
        assertThat(getUsingVThread(() ->
            body.toFile(letsHopeItDoesNotExist, 0, SECONDS, Set.of())))
                .isZero();
        // Post condition (test failed legitimately, or machine is a pedophile?)
        assertThat(notExists(letsHopeItDoesNotExist))
                .isTrue();
    }
    
    private static Request createRequest(
            String reqBody, String... headersNameValuePairs) {
        var line = new RawRequest.Line(
                       "test-method",
                       "test-requestTarget",
                       "test-httpVersion",
                       -1, -1);
        var head = new RawRequest.Head(
                       line,
                       new RequestHeaders(
                             linkedHashMap(headersNameValuePairs)));
        var body = RequestBody.of(
                       head.headers(),
                       new ChannelReader(
                             ofString(reqBody), mock(IdleConnTimeout.class)));
        var skel = new SkeletonRequest(
                       head,
                       HTTP_1_1,
                       parse("/?"),
                       body,
                       // Not reading trailers
                       null);
        return requestWithoutParams(skel);
    }
    
    private static Request createEmptyRequest() {
        return createRequest("");
    }
}