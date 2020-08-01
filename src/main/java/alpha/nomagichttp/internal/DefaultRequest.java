package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.PooledByteBufferHolder;
import alpha.nomagichttp.message.Request;

import java.io.IOException;
import java.net.http.HttpHeaders;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.charset.Charset;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.BiFunction;

import static alpha.nomagichttp.message.Headers.contentType;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.Optional.ofNullable;

final class DefaultRequest implements Request
{
    // Copy-pasted from AsynchronousFileChannel.NO_ATTRIBUTES
    @SuppressWarnings({"unchecked", "rawtypes"}) // generic array construction
    private static final FileAttribute<?>[] NO_ATTRIBUTES = new FileAttribute[0];
    
    private final RequestHead head;
    private final Map<String, String> pathParameters;
    private final Flow.Publisher<PooledByteBufferHolder> channel;
    
    DefaultRequest(
            RequestHead head,
            Map<String, String> pathParameters,
            Flow.Publisher<PooledByteBufferHolder> channel)
    {
        this.head = head;
        this.pathParameters = pathParameters;
        this.channel = channel;
    }
    
    @Override
    public String method() {
        return head.method();
    }
    
    @Override
    public String target() {
        return head.requestTarget();
    }
    
    @Override
    public String httpVersion() {
        return head.httpVersion();
    }
    
    @Override
    public String toString() {
        return DefaultRequest.class.getSimpleName() + "{head=" + head + ", body=?}";
    }
    
    @Override
    public Optional<String> paramFromPath(String name) {
        return ofNullable(pathParameters.get(name));
    }
    
    @Override
    public Optional<String> paramFromQuery(String name) {
        throw new AbstractMethodError("Implement me.");
    }
    
    @Override
    public HttpHeaders headers() {
        return head.headers();
    }
    
    private Body body;
    
    @Override
    public Body body() {
        Body b = body;
        return b != null ? b : (body = new DefaultBody());
    }
    
    private final class DefaultBody implements Request.Body
    {
        @Override
        public CompletionStage<String> toText() {
            Charset charset = contentType(headers())
                    .filter(m -> m.type().equals("text"))
                    .map(MediaType::parameters)
                    .map(p -> p.get("charset"))
                    .map(Charset::forName)
                    .orElse(UTF_8);
            
            return convert((buf, count) ->
                    new String(buf, 0, count, charset));
        }
        
        @Override
        public CompletionStage<Long> toFile(Path file, OpenOption... options) {
            return toFile(file, Set.of(options), NO_ATTRIBUTES);
        }
        
        @Override
        public CompletionStage<Long> toFile(Path file, Set<? extends OpenOption> options, FileAttribute<?>... attrs) {
            final Set<? extends OpenOption> opt = !options.isEmpty() ? options :
                    Set.of(WRITE, CREATE, TRUNCATE_EXISTING);
            
            final AsynchronousFileChannel fs;
            
            try {
                // TODO: Use server's thread pool (must expose it through API)
                fs = AsynchronousFileChannel.open(file, opt, null, attrs);
            } catch (IOException e) {
                return CompletableFuture.failedStage(e);
            }
            
            FileSubscriber s = new FileSubscriber(fs);
            subscribe(s);
            return s.asCompletionStage();
        }
        
        @Override
        public <R> CompletionStage<R> convert(BiFunction<byte[], Integer, R> f) {
            HeapSubscriber<R> s = new HeapSubscriber<>(f);
            subscribe(s);
            return s.asCompletionStage();
        }
        
        @Override
        public void subscribe(Flow.Subscriber<? super PooledByteBufferHolder> subscriber) {
            channel.subscribe(subscriber);
        }
    }
}