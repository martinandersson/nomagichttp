package alpha.nomagichttp.handler;

import alpha.nomagichttp.message.MediaRange;
import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;

import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import static alpha.nomagichttp.message.MediaType.*;
import static java.text.MessageFormat.format;
import static java.util.Objects.requireNonNull;

/**
 * Default implementation of {@link Handler}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class DefaultHandler implements Handler
{
    private final String method;
    private final MediaType consumes;
    private final MediaType produces;
    private final Function<Request, CompletionStage<Response>> logic;
    private final int hash;
    
    DefaultHandler(
            String method,
            MediaType consumes,
            MediaType produces,
            Function<Request, CompletionStage<Response>> logic)
    {
        this.method   = requireNonNull(method);
        this.consumes = validateConsumes(consumes);
        this.produces = validateProduces(produces);
        this.logic    = requireNonNull(logic);
        this.hash     = Objects.hash(method, consumes, produces);;
    }
    
    @Override
    public String method() {
        return method;
    }
    
    @Override
    public MediaType consumes() {
        return consumes;
    }
    
    @Override
    public MediaType produces() {
        return produces;
    }
    
    @Override
    public Function<Request, CompletionStage<Response>> logic() {
        return logic;
    }
    
    @Override
    public int hashCode() {
        return hash;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        
        if (obj == null) {
            return false;
        }
        
        if (DefaultHandler.class != obj.getClass()) {
            return false;
        }
        
        DefaultHandler other = (DefaultHandler) obj;
        
        return this.method.equals(other.method) &&
               this.consumes.equals(other.consumes) &&
               this.produces.equals(other.produces);
    }
    
    @Override
    public String toString() {
        StringJoiner contents = new StringJoiner(", ")
                .add("method=\""   + method   + "\"")
                .add("consumes=\"" + consumes + "\"")
                .add("produces=\"" + produces + "\"");
        
        return DefaultHandler.class.getSimpleName() + '{' + contents + '}';
    }
    
    private static MediaType validateConsumes(MediaType consumes) {
        return requireQualityOne(requireNonNull(consumes));
    }
    
    private static MediaType validateProduces(MediaType produces) {
        requireQualityOne(requireNonNull(produces));
        requireNotSame(produces, NOTHING);
        requireNotSame(produces, WHATEVER);
        return produces;
    }
    
    private static MediaType requireQualityOne(MediaType type) {
        if (type instanceof MediaRange && ((MediaRange) type).quality() != 1.) {
            throw new IllegalArgumentException(
                    "A handler can not consume or produce a media range with a different quality than 1.");
        }
        
        return type;
    }
    
    private static void requireNotSame(MediaType produces, MediaType invalid) {
        if (produces == invalid) {
            throw new IllegalArgumentException(format(
                    "Handler's producing media type must not be \"{0}\". Maybe try \"{1}\"?",
                    invalid, ALL));
        }
    }
}