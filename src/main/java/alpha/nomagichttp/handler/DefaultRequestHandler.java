package alpha.nomagichttp.handler;

import alpha.nomagichttp.message.MediaRange;
import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.Request;

import java.util.Objects;
import java.util.StringJoiner;
import java.util.function.BiConsumer;

import static alpha.nomagichttp.message.MediaType.__ALL;
import static alpha.nomagichttp.message.MediaType.__NOTHING;
import static alpha.nomagichttp.message.MediaType.__NOTHING_AND_ALL;
import static java.text.MessageFormat.format;
import static java.util.Objects.requireNonNull;

/**
 * Default implementation of {@link RequestHandler}.<p>
 * 
 * Nothing special about this guy, or the builder. They are both value objects.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class DefaultRequestHandler implements RequestHandler
{
    private final String m;
    private final MediaType c, p;
    private final BiConsumer<Request, ClientChannel> l;
    private final int h;
    
    DefaultRequestHandler(
            String method,
            MediaType consumes,
            MediaType produces,
            BiConsumer<Request, ClientChannel> logic)
    {
        m = requireNonNull(method);
        c = consumes;
        p = produces;
        l = requireNonNull(logic);
        h = Objects.hash(method, consumes, produces);
        
        if (c != null) {
            validateConsumes(c);
        }
        if (p != null) {
            validateProduces(p);
        }
    }
    
    @Override
    public String method() {
        return m;
    }
    
    @Override
    public MediaType consumes() {
        return c == null ? RequestHandler.super.consumes() : c;
    }
    
    @Override
    public MediaType produces() {
        return p == null ? RequestHandler.super.produces() : p;
    }
    
    @Override
    public BiConsumer<Request, ClientChannel> logic() {
        return l;
    }
    
    @Override
    public int hashCode() {
        return h;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        
        if (obj == null) {
            return false;
        }
        
        if (DefaultRequestHandler.class != obj.getClass()) {
            return false;
        }
        
        DefaultRequestHandler other = (DefaultRequestHandler) obj;
        
        return this.m.equals(other.m) &&
               this.consumes().equals(other.consumes()) &&
               this.produces().equals(other.produces());
    }
    
    @Override
    public String toString() {
        StringJoiner contents = new StringJoiner(", ")
                .add("method=\"" + m + "\"")
                .add("consumes=\"" + consumes() + "\"")
                .add("produces=\"" + produces() + "\"")
                .add("logic=?");
        
        return DefaultRequestHandler.class.getSimpleName() + '{' + contents + '}';
    }
    
    private static void validateConsumes(MediaType c) {
        requireQualityOne(c);
    }
    
    private static void validateProduces(MediaType p) {
        requireQualityOne(p);
        requireNotSame(p, __NOTHING);
        requireNotSame(p, __NOTHING_AND_ALL);
    }
    
    private static void requireQualityOne(MediaType type) {
        if (type instanceof MediaRange && ((MediaRange) type).quality() != 1.) {
            throw new IllegalArgumentException(
                    "A handler can not consume or produce a media range with a different quality than 1.");
        }
    }
    
    private static void requireNotSame(MediaType produces, MediaType invalid) {
        if (produces == invalid) {
            throw new IllegalArgumentException(format(
                    "Handler's producing media type must not be \"{0}\". Maybe try \"{1}\"?",
                    invalid, __ALL));
        }
    }
    
    /**
     * Default implementation of {@link RequestHandler.Builder}.
     * 
     * @author Martin Andersson (webmaster at martinandersson.com)
     */
    static class Builder implements RequestHandler.Builder {
        private final String m;
        private MediaType c, p;
        private BiConsumer<Request, ClientChannel> l;
        
        Builder(String method) {
            if (method.isEmpty()) {
                throw new IllegalArgumentException("Empty method.");
            }
            
            if (method.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException(
                        "Whitespace in method \"" + method + "\".");
            }
            
            m = method;
        }
        
        @Override
        public Builder consumes(MediaType c) {
            this.c = requireNonNull(c);
            return this;
        }
        
        @Override
        public Builder produces(MediaType p) {
            this.p = requireNonNull(p);
            return this;
        }
        
        @Override
        public RequestHandler accept(BiConsumer<Request, ClientChannel> l) {
            return new DefaultRequestHandler(m, c, p, l);
        }
    }
}