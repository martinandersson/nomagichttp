package alpha.nomagichttp.handler;

import alpha.nomagichttp.message.MediaType;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static alpha.nomagichttp.message.MediaType.*;
import static alpha.nomagichttp.message.Responses.accepted;
import static java.util.Objects.requireNonNull;

/**
 * TODO: Docs
 * 
 * LastStep
 *   run    = logic to run, don't care about request and has no response body ... 202
 *   accept = logic to run, does care about request but has no response body  ... 202
 *   apply  = logic cares about request and has a response body
 */
public final class HandlerBuilder
{
    private final String method;
    
    public HandlerBuilder(String method) {
        this.method = requireNonNull(method);
    }
    
    private MediaType consumes;
    
    public NextStep consumesNothing() {
        return consumes(NOTHING);
    }
    
    public NextStep consumesAll() {
        return consumes(ALL);
    }
    
    public NextStep consumesNothingAndAll() {
        return consumes(NOTHING_AND_ALL);
    }
    
    public NextStep consumesTextPlain() {
        return consumes(TEXT_PLAIN);
    }
    
    public NextStep consumes(String mediaType) {
        return consumes(parse(mediaType));
    }
    
    public NextStep consumes(MediaType mediaType) {
        consumes = mediaType;
        return new NextStep(this);
    }
    
    // TODO: Lots more
    
    public static final class NextStep extends Link<HandlerBuilder> {
        NextStep(HandlerBuilder builder) {
            super(builder);
        }
        
        private MediaType produces;
        
        public LastStep producesAll() {
            return produces(ALL);
        }
        
        public LastStep producesTextPlain() {
            return produces(TEXT_PLAIN);
        }
        
        public LastStep produces(String mediaType) {
            return produces(parse(mediaType));
        }
        
        public LastStep produces(MediaType mediaType) {
            produces = mediaType;
            return new LastStep(this);
        }
        
        // TODO: Lots more
    }
    
    public static final class LastStep extends Link<NextStep> {
        LastStep(NextStep prev) {
            super(prev);
        }
        
        // TODO: 202 Accepted
        public Handler run(Runnable logic) {
            requireNonNull(logic);
            return accept(requestIgnored -> logic.run());
        }
        
        public Handler supply(Supplier<CompletionStage<Response>> logic) {
            requireNonNull(logic);
            return apply(requestIgnored -> logic.get());
        }
        
        // TODO: 202 Accepted
        public Handler accept(Consumer<Request> logic) {
            requireNonNull(logic);
            return apply(req -> {
                logic.accept(req);
                return accepted().asCompletedStage();
            });
        }
        
        public Handler apply(Function<Request, CompletionStage<Response>> logic) {
            return new DefaultHandler(
                    prev.prev.method,
                    prev.prev.consumes,
                    prev.produces,
                    logic);
        }
    }
    
    private static abstract class Link<P> {
        final P prev;
        
        Link(P prev) {
            this.prev = prev;
        }
    }
}