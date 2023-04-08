package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.message.BadHeaderException;
import alpha.nomagichttp.message.RawRequest;
import alpha.nomagichttp.message.Request;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import static alpha.nomagichttp.Config.DEFAULT;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.internal.DefaultRequest.requestWithoutParams;
import static alpha.nomagichttp.internal.SkeletonRequestTarget.parse;
import static alpha.nomagichttp.testutil.ReadableByteChannels.ofString;
import static alpha.nomagichttp.util.DummyScopedValue.where;
import static alpha.nomagichttp.util.Headers.of;
import static alpha.nomagichttp.util.ScopedValues.__HTTP_SERVER;
import static java.nio.file.Files.notExists;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Small tests of {@link DefaultRequest}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class DefaultRequestTest
{
    @Test
    void body_toText_happyPath() throws Exception {
        var req = createRequest(of("Content-Length", "3"), "abc");
        
        // Implementation needs access to Config.maxRequestBodyConversionSize()
        var server = mock(HttpServer.class);
        when(server.getConfig()).thenReturn(DEFAULT);
        
        final String str = where(__HTTP_SERVER, server, () -> {
            // ...and a virtual thread, otherwise WrongThreadException
            try (var vThread = newVirtualThreadPerTaskExecutor()) {
                return vThread.submit(() -> req.body().toText()).get();
            }
        });
        
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
    void body_toFile_empty()
            throws InterruptedException, ExecutionException {
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
    
    private static long getUsingVThread(Callable<Long> task)
            throws InterruptedException, ExecutionException {
        try (var exec = newVirtualThreadPerTaskExecutor()) {
            return exec.submit(task).get();
        }
    }
    
    private static Request createRequest(
            LinkedHashMap<String, List<String>> headers, String reqBody) {
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