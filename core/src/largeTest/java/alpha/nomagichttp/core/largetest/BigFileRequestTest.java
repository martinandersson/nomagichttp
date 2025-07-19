package alpha.nomagichttp.core.largetest;

import alpha.nomagichttp.core.largetest.util.AbstractLargeRealTest;
import alpha.nomagichttp.core.largetest.util.DataUtils;
import alpha.nomagichttp.testutil.functional.HttpClientFacade;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.ClassOrderer.OrderAnnotation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.opentest4j.TestAbortedException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.noContent;
import static alpha.nomagichttp.message.Responses.ok;
import static alpha.nomagichttp.testutil.TestConstants.CRLF;
import static alpha.nomagichttp.testutil.functional.Constants.OTHER;
import static alpha.nomagichttp.testutil.functional.Constants.TEST_CLIENT;
import static alpha.nomagichttp.testutil.functional.HttpClientFacade.Implementation.JETTY;
import static alpha.nomagichttp.testutil.functional.HttpClientFacade.Implementation.REACTOR;
import static alpha.nomagichttp.util.ByteBufferIterables.ofFile;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * POST a big file (40 MB) to server, verify disk contents, respond the file.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
@TestClassOrder(OrderAnnotation.class)
final class BigFileRequestTest extends AbstractLargeRealTest
{
    private static final int FILE_SIZE = 40 * 1_000_000;
    
    private static Path file;
    private static byte[] contents;
    private static boolean saved;
    
    @BeforeAll
    void beforeAll() throws IOException {
        file = Files.createTempDirectory("nomagic").resolve("big-file");
        contents = DataUtils.bytes(FILE_SIZE);
        // Receive file and respond the length in header
        var post = POST().apply(req -> {
            var len = req.body().toFile(file, WRITE, CREATE, TRUNCATE_EXISTING);
            return noContent().toBuilder()
                    .setHeader("Received", Long.toString(len))
                    .build();
        });
        // Retrieve the file
        var get = GET().apply(_ -> ok(ofFile(file)));
        server().add("/file", post, get);
    }
    
    @Nested
    @Order(1)
    // Could add: @TestInstance(PER_CLASS) ... but doesn't matter
    class Post {
        @Test
        @DisplayName(TEST_CLIENT)
        void testClient() throws IOException {
            final String rsp;
            try (var _ = client().openConnection()) {
                rsp = client().write(
                      "POST /file HTTP/1.1"          + CRLF +
                      "Content-Length: " + FILE_SIZE + CRLF + CRLF)
                    // Give a little bit extra time (1 sec per 10Mb)
                    .interruptWriteAfter(5, SECONDS)
                    .write(
                      contents)
                    .shutdownOutput()
                    .readTextUntilEOS();
            }
            assertThat(rsp).isEqualTo(
                "HTTP/1.1 204 No Content" + CRLF +
                "Received: " + FILE_SIZE  + CRLF + CRLF);
            assertFileContentsOnDisk();
            saved = true;
        }
        
        @ParameterizedTest(name = OTHER)
        @EnumSource
        void compatibility(HttpClientFacade.Implementation impl)
                throws IOException, ExecutionException,
                       InterruptedException, TimeoutException
        {
            if (impl == REACTOR) {
                // Reactor does what Reactor does best: NPE
                throw new TestAbortedException();
            }
            var rsp = impl.create(serverPort())
                    .postBytesAndReceiveEmpty("/file", HTTP_1_1, contents);
            assertFileContentsOnDisk();
            assertThat(rsp.version()).isEqualTo("HTTP/1.1");
            assertThat(rsp.statusCode()).isEqualTo(204);
            assertThat(rsp.headers().firstValueAsLong("Received")).hasValue(FILE_SIZE);
        }
        
        private static void assertFileContentsOnDisk() throws IOException {
            assertThat(Files.readAllBytes(file)).isEqualTo(contents);
        }
    }
    
    @Nested
    @Order(2)
    class Get {
        @Test
        @DisplayName(TEST_CLIENT)
        void testClient() throws IOException {
            assumeTrue(saved);
            final ByteBuffer body;
            try (var _ = client().openConnection()) {
                String head = client().write(
                          "GET /file HTTP/1.1" + CRLF + CRLF)
                        .shutdownOutput()
                        .readTextUntilNewlines();
                assertThat(head).isEqualTo(
                    "HTTP/1.1 200 OK"                        + CRLF +
                    "Content-Type: application/octet-stream" + CRLF +
                    "Content-Length: " + FILE_SIZE           + CRLF + CRLF);
                /*
                 * Reactor takes about 1.5 seconds, and all other clients from
                 * 0.8 to 1 second.
                 * 
                 * The TestClient can sometimes take time to complete. Most of
                 * the time — on author's machine — it takes roughly 2.2
                 * seconds. As many as 5 seconds were observed.
                 * 
                 * This may be because the TestClient is the first one to
                 * execute. E.g., the file system is slow on the first read
                 * access of the file.
                 */
                body = client().interruptReadAfter(8, SECONDS)
                               .responseBufferInitialSize(contents.length)
                               .readBytesUntilEOS();
            }
            assertThat(body.remaining()).isEqualTo(contents.length);
            for (byte b : contents) {
                assertEquals(body.get(), b);
            }
        }
        
        @ParameterizedTest(name = OTHER)
        @EnumSource
        void compatibility(HttpClientFacade.Implementation impl)
                throws IOException, ExecutionException,
                       InterruptedException, TimeoutException
        {
            assumeTrue(saved);
            if (impl == JETTY) {
                // Jetty has some kind of internal capacity buffer constraint.
                //   java.lang.IllegalArgumentException: Buffering capacity 2097152 exceeded
                // There's a fix for it:
                // https://stackoverflow.com/questions/65941299/buffering-capacity-2097152-exceeded-from-jetty-when-response-is-large
                // ..but I'm not too keen wasting time tweaking individual clients
                // when all others work.
                throw new TestAbortedException();
            }
            HttpClientFacade.ResponseFacade<byte[]> rsp
                    = impl.create(serverPort()).getBytes("/file", HTTP_1_1);
            assertThat(rsp.version()).isEqualTo("HTTP/1.1");
            assertThat(rsp.statusCode()).isEqualTo(200);
            assertThat(rsp.body()).isEqualTo(contents);
        }
    }
}
