package alpha.nomagichttp.largetest;

import alpha.nomagichttp.testutil.AbstractLargeRealTest;
import alpha.nomagichttp.testutil.HttpClientFacade;
import alpha.nomagichttp.testutil.Logging;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.opentest4j.TestAbortedException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.noContent;
import static alpha.nomagichttp.message.Responses.ok;
import static alpha.nomagichttp.testutil.HttpClientFacade.Implementation.APACHE;
import static alpha.nomagichttp.testutil.HttpClientFacade.Implementation.JETTY;
import static alpha.nomagichttp.testutil.HttpClientFacade.Implementation.REACTOR;
import static alpha.nomagichttp.testutil.TestClient.CRLF;
import static alpha.nomagichttp.util.BetterBodyPublishers.ofFile;
import static java.lang.System.Logger.Level.ALL;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * POST a big file (50 MB) to server, verify disk contents, respond same file.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
@TestMethodOrder(OrderAnnotation.class)
class BigFileRequestTest extends AbstractLargeRealTest
{
    private final static int FILE_SIZE = 50 * 1_000_000;
    
    private static Path file;
    private static byte[] contents;
    private static boolean saved;
    
    @BeforeAll
    void beforeAll() throws IOException {
        // Crashes far too often in exceptional situations, so, for debugging:
        Logging.setLevel(ALL);
        
        file = Files.createTempDirectory("nomagic").resolve("big-file");
        contents = DataUtil.bytes(FILE_SIZE);
        server().add("/file",
            GET().respond(ok(ofFile(file))),
            POST().apply(req ->
                req.body().toFile(file, WRITE, CREATE, TRUNCATE_EXISTING)
                          .thenApply(len -> noContent().toBuilder().addHeaders(
                                  "Received", Long.toString(len),
                                  "Connection", "close")
                                .build())));
    }
    
    @Test
    @DisplayName("post/TestClient")
    @Order(1)
    void post() throws IOException {
        final String rsp;
        Channel conn = client().openConnection();
        try (conn) {
            rsp = client()
                      .write(
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
            "Received: " + FILE_SIZE  + CRLF +
            "Connection: close"       + CRLF + CRLF);
        assertFileContentsOnDisk();
        saved = true;
    }
    
    @Test
    @DisplayName("get/TestClient")
    @Order(2)
    void get() throws IOException {
        assumeTrue(saved);
        final ByteBuffer body;
        Channel conn = client().openConnection();
        try (conn) {
            String req = "GET /file HTTP/1.1" + CRLF +
                         "Connection: close"  + CRLF + CRLF;
            String head = client().write(req)
                                  .shutdownOutput()
                                  .readTextUntilNewlines();
            
            assertThat(head).isEqualTo(
                "HTTP/1.1 200 OK"                        + CRLF +
                "Content-Type: application/octet-stream" + CRLF +
                // (No Content-Length. File did not exist at time-of-size check.)
                "Connection: close"                      + CRLF + CRLF);
            
            // The TestClient can sometimes take time to complete. Most of the
            // time - on my machine - it takes roughly 2.2 seconds. Reactor
            // about 1.5 seconds, and all other clients from 0.8 to 1 second.
            // The time cost for the TestClient can be significant, observed as
            // much as 5 seconds. Can also be because the TestClient is first to
            // execute, i.e. it could be the file system who is at fault, being
            // slow on the first read access of the file.
            body = client().interruptReadAfter(8, SECONDS)
                           .responseBufferInitialSize(contents.length)
                           .readBytesUntilEOS();
        }
        assertThat(body.remaining()).isEqualTo(contents.length);
        for (byte b : contents) {
            assertEquals(body.get(), b);
        }
    }
    
    @ParameterizedTest(name = "post/{0}")
    @EnumSource
    void post_compatibility(HttpClientFacade.Implementation impl)
            throws IOException, ExecutionException, InterruptedException, TimeoutException
    {
        assumeTrue(saved);
        
        if (impl == REACTOR) {
            // Reactor do what Reactor does best: NPE.
            throw new TestAbortedException();
        }
        
        var rsp = impl.create(serverPort())
                .postBytesAndReceiveEmpty("/file", HTTP_1_1, contents);
        
        assertFileContentsOnDisk();
        assertThat(rsp.version()).isEqualTo("HTTP/1.1");
        assertThat(rsp.statusCode()).isEqualTo(204);
        assertThat(rsp.headers().firstValueAsLong("Received")).hasValue(FILE_SIZE);
    }
    
    @ParameterizedTest(name = "get/{0}")
    @EnumSource
    void get_compatibility(HttpClientFacade.Implementation impl)
            throws IOException, ExecutionException, InterruptedException, TimeoutException
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
        
        if (impl == APACHE && "true".equals(System.getenv("GITHUB_ACTIONS"))) {
            // On local Windows WSLs Ubuntu using Java 11+, Apache completes
            // just fine in about half a second, as do all other clients, well,
            // except for Reactor of course which takes about 6 seconds (!).
            // On GitHub Actions + Ubuntu + Java 11, Apache sometimes times out
            // (after 5 seconds), sometimes throw OutOfMemoryError. I suspect a
            // small heap space combined with a not so diligent Apache
            // implementation possibly facing a Java 11 bug. Regardless, pretty
            // clear it's an exceptional situation and so excluded here.
            // TODO: When we release for a Java version greater than 11, remove this.
            throw new TestAbortedException();
        }
        
        var rsp = impl.create(serverPort())
                .getBytes("/file", HTTP_1_1);
        
        assertThat(rsp.version()).isEqualTo("HTTP/1.1");
        assertThat(rsp.statusCode()).isEqualTo(200);
        assertThat(rsp.body()).isEqualTo(contents);
    }
    
    private static void assertFileContentsOnDisk() throws IOException {
        assertThat(Files.readAllBytes(file)).isEqualTo(contents);
    }
}
