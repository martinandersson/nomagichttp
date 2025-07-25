package alpha.nomagichttp.core.mediumtest;

import alpha.nomagichttp.event.RequestHeadReceived;
import alpha.nomagichttp.message.RawRequest;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.route.NoRouteFoundException;
import alpha.nomagichttp.testutil.functional.AbstractRealTest;
import alpha.nomagichttp.testutil.functional.HttpClientFacade;
import alpha.nomagichttp.util.Throwing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.opentest4j.TestAbortedException;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;

import static alpha.nomagichttp.HttpConstants.HeaderName.CONTENT_LENGTH;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.handler.RequestHandler.POST;
import static alpha.nomagichttp.message.Responses.noContent;
import static alpha.nomagichttp.message.Responses.processing;
import static alpha.nomagichttp.message.Responses.text;
import static alpha.nomagichttp.testutil.Headers.treeMap;
import static alpha.nomagichttp.testutil.TestConstants.CRLF;
import static alpha.nomagichttp.testutil.functional.Constants.OTHER;
import static alpha.nomagichttp.testutil.functional.Constants.TEST_CLIENT;
import static alpha.nomagichttp.testutil.functional.HttpClientFacade.Implementation.JDK;
import static alpha.nomagichttp.testutil.functional.HttpClientFacade.Implementation.JETTY;
import static alpha.nomagichttp.testutil.functional.HttpClientFacade.Implementation.OKHTTP;
import static alpha.nomagichttp.testutil.functional.HttpClientFacade.Implementation.REACTOR;
import static alpha.nomagichttp.testutil.functional.HttpClientFacade.ResponseFacade;
import static alpha.nomagichttp.util.ScopedValues.channel;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mimics all the examples provided in {@link alpha.nomagichttp.examples}.<p>
 * 
 * The purpose is to have a guarantee that new code changes won't break the
 * examples, and they are compatible across different clients.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see DetailTest
 */
final class ExampleTest extends AbstractRealTest
{
    @Nested
    class HelloWorld {
        @Test
        @DisplayName(TEST_CLIENT)
        void testClient() throws IOException {
            addRoute();
            
            String req =
                "GET /hello HTTP/1.1"               + CRLF +
                "Accept: text/plain; charset=utf-8" + CRLF + CRLF;
            
            String res = client().writeReadTextUntil(req, "World!");
            
            assertThat(res).isEqualTo(
                "HTTP/1.1 200 OK"                         + CRLF +
                "Content-Type: text/plain; charset=utf-8" + CRLF +
                "Content-Length: 12"                      + CRLF + CRLF +
                
                "Hello World!");
        }
        
        @ParameterizedTest(name = OTHER)
        @EnumSource // <-- in case JUnit didn't know an enum parameter is a..
        void compatibility(HttpClientFacade.Implementation impl)
                throws IOException, InterruptedException,
                       TimeoutException, ExecutionException
        {
            addRoute();
            
            ResponseFacade<String> rsp = impl.create(serverPort())
                    .addClientHeader("Accept", "text/plain; charset=utf-8")
                    .getText("/hello", HTTP_1_1);
            
            assertThat(rsp.version()).isEqualTo("HTTP/1.1");
            assertThat(rsp.statusCode()).isEqualTo(200);
            if (impl != JDK) {
                // Assume all other supports retrieving the reason-phrase
                assertThat(rsp.reasonPhrase()).isEqualTo("OK");
            }
            assertThat(treeMap(rsp.headers())).isEqualTo(treeMap(
                "Content-Type",   "text/plain; charset=" +
                    // Lowercase version is what the server sends.
                    (impl == JETTY ? "UTF-8" : "utf-8"),
                "Content-Length", "12"));
            assertThat(rsp.body()).isEqualTo(
                "Hello World!");
        }
        
        private void addRoute() throws IOException {
            server().add("/hello", GET().apply(
                  respondHelloPlus(_ -> "World")));
        }
    }
    
    @Nested
    class GreetParameter {
        @Test
        @DisplayName(TEST_CLIENT)
        void testClient() throws IOException {
            addRoutes();
            
            String req1 =
                "GET /hello/John HTTP/1.1"          + CRLF +
                "Accept: text/plain; charset=utf-8" + CRLF + CRLF;
            
            String req2 =
                "GET /hello?name=John HTTP/1.1"     + CRLF +
                "Accept: text/plain; charset=utf-8" + CRLF + CRLF;
            
            String res1 = client().writeReadTextUntil(req1, "John!");
            assertThat(res1).isEqualTo(
                "HTTP/1.1 200 OK"                         + CRLF +
                "Content-Type: text/plain; charset=utf-8" + CRLF +
                "Content-Length: 11"                      + CRLF + CRLF +
                
                "Hello John!");
            
            String res2 = client().writeReadTextUntil(req2, "John!");
            assertThat(res2).isEqualTo(res1);
        }
        
        @ParameterizedTest(name = "PathParam/" + OTHER)
        @EnumSource
        void compatibility_pathParam(HttpClientFacade.Implementation impl)
                throws IOException, ExecutionException,
                       InterruptedException, TimeoutException {
            engine(impl, "/hello/John");
        }
        
        @ParameterizedTest(name = "Query/" + OTHER)
        @EnumSource
        void compatibility_query(HttpClientFacade.Implementation impl)
                throws IOException, ExecutionException,
                       InterruptedException, TimeoutException {
            engine(impl, "/hello?name=John");
        }
        
        private void engine(
                HttpClientFacade.Implementation impl, String reqTarget)
                throws IOException, ExecutionException,
                       InterruptedException, TimeoutException
        {
            addRoutes();
            
            HttpClientFacade req = impl.create(serverPort())
                    .addClientHeader("Accept", "text/plain; charset=utf-8");
            
            var rsp = req.getText(reqTarget, HTTP_1_1);
            
            assertThat(rsp.version()).isEqualTo("HTTP/1.1");
            assertThat(rsp.statusCode()).isEqualTo(200);
            if (impl != JDK) {
                // Assume all other supports retrieving the reason-phrase
                assertThat(rsp.reasonPhrase()).isEqualTo("OK");
            }
            // JDK and OkHTTP will lower-case all keys
            assertThat(treeMap(rsp.headers())).isEqualTo(treeMap(
                "Content-Type",   "text/plain; charset=" +
                    (impl == JETTY ? "UTF-8" : "utf-8"),
                "Content-Length", "11"));
            assertThat(rsp.body()).isEqualTo(
                "Hello John!");
        }
        
        private void addRoutes() throws IOException {
            // Name from path
            server().add("/hello/:name", GET().apply(
                  respondHelloPlus(req -> req.target().pathParam("name"))));
            // Name from query
            server().add("/hello", GET().apply(
                  respondHelloPlus(req -> req.target().queryFirst("name").get())));
        }
    }
    
    @Nested
    class GreetBody {
        @Test
        @DisplayName(TEST_CLIENT)
        void testClient() throws IOException {
            addRoute();
            
            String req =
                "POST /hello HTTP/1.1"                    + CRLF +
                "Accept: text/plain; charset=utf-8"       + CRLF +
                "Content-Length: 4"                       + CRLF +
                "Content-Type: text/plain; charset=utf-8" + CRLF + CRLF +
                
                "John";
            
            String res = client().writeReadTextUntil(req, "John!");
            
            assertThat(res).isEqualTo(
                "HTTP/1.1 200 OK"                         + CRLF +
                "Content-Type: text/plain; charset=utf-8" + CRLF +
                "Content-Length: 11"                      + CRLF + CRLF +
                
                "Hello John!");
        }
        
        @ParameterizedTest(name = OTHER)
        @EnumSource
        void compatibility(HttpClientFacade.Implementation impl)
                throws IOException, InterruptedException,
                       ExecutionException, TimeoutException
        {
            addRoute();
            
            HttpClientFacade req = impl.create(serverPort())
                    .addClientHeader("Accept", "text/plain; charset=utf-8");
            
            ResponseFacade<String> rsp = req.postAndReceiveText(
                    "/hello", HTTP_1_1, "John");
            
            assertThat(rsp.version()).isEqualTo("HTTP/1.1");
            assertThat(rsp.statusCode()).isEqualTo(200);
            if (impl != JDK) {
                assertThat(rsp.reasonPhrase()).isEqualTo("OK");
            }
            assertThat(treeMap(rsp.headers())).isEqualTo(treeMap(
                "Content-Length", "11",
                "Content-Type",   "text/plain; charset=" +
                    (impl == JETTY ? "UTF-8" : "utf-8")));
            assertThat(rsp.body()).isEqualTo(
                "Hello John!");
        }
        
        private void addRoute() throws IOException {
            server().add("/hello", POST().apply(
                  respondHelloPlus(req -> req.body().toText())));
        }
    }
    
    @Nested
    class EchoHeaders {
        @Test
        @DisplayName(TEST_CLIENT)
        void testClient() throws IOException {
            addRoute();
            
            String req =
                "GET /echo HTTP/1.1"        + CRLF +
                "My-Header: Value 1"        + CRLF +
                "My-Header: Value 2"        + CRLF + CRLF;
            
            String res = client().writeReadTextUntilNewlines(req);
            
            assertThat(res).isEqualTo(
                "HTTP/1.1 204 No Content"   + CRLF +
                "My-Header: Value 1"        + CRLF + 
                "My-Header: Value 2"        + CRLF + CRLF);
        }
        
        @ParameterizedTest(name = OTHER)
        @EnumSource
        void compatibility(HttpClientFacade.Implementation impl)
                throws IOException, ExecutionException,
                       InterruptedException, TimeoutException
        {
            if (impl == OKHTTP) {
                // OkHttp will drop "Value 1" and only report "Value 2".
                // Am I surprised? No. Do I care? No.
                // Is it excluded from the test so that my life can go on? Yes.
                throw new TestAbortedException();
            }
            
            addRoute();
            
            var rsp = impl.create(serverPort())
                    .addClientHeader("My-Header", "Value 1")
                    .addClientHeader("My-Header", "Value 2")
                    .getEmpty("/echo", HTTP_1_1);
            
            assertThat(rsp.version()).isEqualTo("HTTP/1.1");
            assertThat(rsp.statusCode()).isEqualTo(204);
            if (impl != JDK) {
                assertThat(rsp.reasonPhrase()).isEqualTo("No Content");
            }
            
            // The clients send - and thus receive - different headers.
            // Most obviously the value of the "Host: " header.
            // What we care about here is getting our custom headers back.
            assertThat(rsp.headers().allValues(
                "My-Header")).containsExactly("Value 1", "Value 2");
        }
        
        private void addRoute() throws IOException {
            server().add("/echo", GET().apply(req -> {
                var b = noContent().toBuilder();
                for (var entry : req.headers()) {
                    
                    var k = entry.getKey();
                    if (!k.equalsIgnoreCase(CONTENT_LENGTH)) {
                        for (var vals : entry.getValue()) {
                            b = b.addHeader(k, vals);
                        }
                    }
                }
                return b.build();
            }));
        }
    }
    
    @Nested
    class KeepClientInformed {
        @Test
        @DisplayName(TEST_CLIENT)
        public void testClient() throws IOException {
            addRoute();
            // TODO: This isn't up to date with the example.
            //       Also revise the other test cases.
            //       We want a one-to-one exchange replica for each example.
            String rsp = client().writeReadTextUntil(
                "GET / HTTP/1.1" + CRLF + CRLF, "Done!");
            assertThat(rsp).isEqualTo(
                "HTTP/1.1 102 Processing"                 + CRLF + CRLF +
                
                "HTTP/1.1 102 Processing"                 + CRLF + CRLF +
                
                "HTTP/1.1 200 OK"                         + CRLF +
                "Content-Type: text/plain; charset=utf-8" + CRLF +
                "Content-Length: 5"                       + CRLF + CRLF +
                
                "Done!");
        }
        
        // Verified to work also on Curl
        @ParameterizedTest(name = OTHER)
        @EnumSource
        public void compatibility(HttpClientFacade.Implementation impl)
                throws IOException, ExecutionException,
                       InterruptedException, TimeoutException
        {
            if (impl == OKHTTP || impl == REACTOR) {
                // OkHttp throws "IllegalStateException: state: 3"
                // Reactor does what Reactor does best; crashes with NPE.
                throw new TestAbortedException();
            }
            addRoute();
            var rsp = impl.create(serverPort()).getText("/", HTTP_1_1);
            assertThat(rsp.body()).isEqualTo("Done!");
        }
        
        private void addRoute() throws IOException {
            server().add("/", GET().apply(_ -> {
                var ch = channel();
                ch.write(processing());
                ch.write(processing());
                return text("Done!");
            }));
        }
    }
    
    // TODO: Make public
    @Nested
    class NonPublicExamples {
        // TODO: Will wait until after we have done improved file serving.
        //       The compatibility test ought to use a client-native API for uploading a file.
        
        // TODO: Add examples for streaming, both 102 (Processing) and infinite bodies
        
        @Test
        @DisplayName("UploadFile/TestClient")
        void UploadFile() throws IOException, InterruptedException {
            // Destination file
            Path file = Files.createTempDirectory("nomagic")
                    .resolve("some-file.txt");
            
            // Handler saves the file bytes and return byte count as response body
            server().add("/small-file", POST().apply(req ->
                    text(Long.toString(req.body().toFile(file)))));
            
            final String reqHead =
                "POST /small-file HTTP/1.1" + CRLF +
                "Content-Length: 3"         + CRLF + CRLF;
            
            String res1 = client().writeReadTextUntil(reqHead + "Foo", "3");
            
            assertThat(res1).isEqualTo(
                "HTTP/1.1 200 OK"                          + CRLF +
                "Content-Type: text/plain; charset=utf-8"  + CRLF +
                "Content-Length: 1"                        + CRLF + CRLF +
                
                "3");
            assertThat(Files.readString(file)).isEqualTo("Foo");
            
            // By default, existing files are not overwritten
            String res2 = client().writeReadTextUntilNewlines(reqHead + "Bar");
            
            assertThat(res2).isEqualTo(
                "HTTP/1.1 500 Internal Server Error" + CRLF +
                "Content-Length: 0"                  + CRLF + CRLF);
            assertAwaitHandledAndLoggedExc()
                    .isExactlyInstanceOf(FileAlreadyExistsException.class);
            assertThat(Files.readString(file))
                    .isEqualTo("Foo");
        }
        
        @Test
        @DisplayName("CountRequestsByMethod/TestClient")
        void CountRequestsByMethod() throws IOException, InterruptedException {
            final var freqs = new ConcurrentHashMap<String, LongAdder>();
            
            BiConsumer<RequestHeadReceived, RawRequest.Head> incrementer = (event, head) ->
                    freqs.computeIfAbsent(head.line().method(), m -> new LongAdder()).increment();
            
            // We don't need to add routes here, sort of the whole point lol
            server().events().on(RequestHeadReceived.class, incrementer);
            // Must await the server before we assert the counter
            var responseIgnored = client()
                    .writeReadTextUntilNewlines("GET / HTTP/1.1" + CRLF + CRLF);
            
            assertThat(freqs.get("GET").sum())
                  .isOne();
            assertAwaitHandledAndLoggedExc()
                  .isExactlyInstanceOf(NoRouteFoundException.class);
        }
        
        /**
         * The happy version of
         * {@link ErrorTest.Special#requestTrailersDiscarded_exceptionNotHandled()}.
         */
        @Test
        @DisplayName("RequestTrailers/TestClient")
        void RequestTrailers() throws IOException {
            // Echo body and append trailer value
            server().add("/", POST().apply(req ->
                    text(req.body().toText() +
                         req.trailers().firstValue("Append-This").get())));
            
            var rsp = client().writeReadTextUntilEOS("""
                    POST / HTTP/1.1
                    Transfer-Encoding: chunked
                    Connection: close
                    
                    6
                    Hello\s
                    0
                    Append-This: World!
                    
                    """);
            
            assertThat(rsp).isEqualTo("""
                    HTTP/1.1 200 OK\r
                    Content-Type: text/plain; charset=utf-8\r
                    Connection: close\r
                    Content-Length: 12\r
                    \r
                    Hello World!""");
        }
    }
    
    private static
        Throwing.Function<Request, Response, IOException> respondHelloPlus(
                Throwing.Function<Request, String, IOException> getName) {
        return req -> text("Hello " + getName.apply(req) + "!");
    }
}
