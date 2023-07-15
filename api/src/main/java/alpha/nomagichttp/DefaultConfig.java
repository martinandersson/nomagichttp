package alpha.nomagichttp;

import alpha.nomagichttp.HttpConstants.Version;
import alpha.nomagichttp.util.AbstractImmutableBuilder;

import java.time.Duration;
import java.util.function.Consumer;

import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_0;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static java.time.Duration.ofSeconds;
import static java.util.Objects.requireNonNull;

/**
 * Default implementation of {@link Config}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class DefaultConfig implements Config {
    private final Builder  builder;
    private final int      maxRequestHeadSize,
                           maxRequestBodyBufferSize,
                           maxRequestTrailersSize,
                           maxErrorResponses;
    private final Version  minHttpVersion;
    private final boolean  discardRejectedInformational,
                           immediatelyContinueExpect100;
    private final Duration timeoutRead,
                           timeoutResponse,
                           timeoutWrite,
                           timeoutFileLock;
    private final boolean  implementMissingOptions;
    
    DefaultConfig(Builder b, DefaultBuilder.MutableState s) {
        builder                      = b;
        maxRequestHeadSize           = s.maxRequestHeadSize;
        maxRequestBodyBufferSize     = s.maxRequestBodyBufferSize;
        maxRequestTrailersSize       = s.maxRequestTrailersSize;
        maxErrorResponses            = s.maxErrorResponses;
        minHttpVersion               = s.minHttpVersion;
        discardRejectedInformational = s.discardRejectedInformational;
        immediatelyContinueExpect100 = s.immediatelyContinueExpect100;
        timeoutRead                  = s.timeoutRead;
        timeoutResponse              = s.timeoutResponse;
        timeoutWrite                 = s.timeoutWrite;
        timeoutFileLock              = s.timeoutFileLock;
        implementMissingOptions      = s.implementMissingOptions;
    }
    
    @Override
    public int maxRequestHeadSize() {
        return maxRequestHeadSize;
    }
    
    @Override
    public int maxRequestBodyBufferSize() {
        return maxRequestBodyBufferSize;
    }
    
    @Override
    public int maxRequestTrailersSize() {
        return maxRequestTrailersSize;
    }
    
    @Override
    public int maxErrorResponses() {
        return maxErrorResponses;
    }
    
    @Override
    public HttpConstants.Version minHttpVersion() {
        return minHttpVersion;
    }
    
    @Override
    public boolean discardRejectedInformational() {
        return discardRejectedInformational;
    }
    
    @Override
    public boolean immediatelyContinueExpect100() {
        return immediatelyContinueExpect100;
    }
    
    @Override
    public Duration timeoutRead() {
        return timeoutRead;
    }
    
    @Override
    public Duration timeoutResponse() {
        return timeoutResponse;
    }
    
    @Override
    public Duration timeoutWrite() {
        return timeoutWrite;
    }
    
    @Override
    public Duration timeoutFileLock() {
        return timeoutFileLock;
    }
    
    @Override
    public boolean implementMissingOptions() {
        return implementMissingOptions;
    }
    
    @Override
    public Builder toBuilder() {
        return builder;
    }
    
    static final class DefaultBuilder
            extends AbstractImmutableBuilder<DefaultBuilder.MutableState>
            implements Builder
    {
        static final DefaultBuilder ROOT = new DefaultBuilder();
        
        static class MutableState {
            int      maxRequestHeadSize           = 8_000,
                     maxRequestBodyBufferSize     = 20_971_520,
                     maxRequestTrailersSize       = 8_000,
                     maxErrorResponses            = 3;
            Version  minHttpVersion               = HTTP_1_0;
            boolean  discardRejectedInformational = true,
                     immediatelyContinueExpect100 = false;
            Duration timeoutRead                  = ofSeconds(90),
                     timeoutResponse              = timeoutRead,
                     timeoutWrite                 = timeoutRead,
                     timeoutFileLock              = ofSeconds(3);
            boolean  implementMissingOptions      = true;
        }
        
        private DefaultBuilder() {
            // super()
        }
        
        private DefaultBuilder(DefaultBuilder prev, Consumer<MutableState> modifier) {
            super(prev, modifier);
        }
        
        @Override
        public Builder maxRequestHeadSize(int newVal) {
            return new DefaultBuilder(this, s -> s.maxRequestHeadSize = newVal);
        }
        
        @Override
        public Builder maxRequestBodyBufferSize(int newVal) {
            return new DefaultBuilder(this, s -> s.maxRequestBodyBufferSize = newVal);
        }
        
        @Override
        public Builder maxErrorResponses(int newVal) {
            return new DefaultBuilder(this, s -> s.maxErrorResponses = newVal);
        }
        
        @Override
        public Builder minHttpVersion(Version newVal) {
            if (newVal.isLessThan(HTTP_1_0) || newVal.isGreaterThan(HTTP_1_1)) {
                throw new IllegalArgumentException();
            }
            return new DefaultBuilder(this, s -> s.minHttpVersion = newVal);
        }
        
        @Override
        public Builder discardRejectedInformational(boolean newVal) {
            return new DefaultBuilder(this, s -> s.discardRejectedInformational = newVal);
        }
        
        @Override
        public Builder immediatelyContinueExpect100(boolean newVal) {
            return new DefaultBuilder(this, s -> s.immediatelyContinueExpect100 = newVal);
        }
        
        @Override
        public Builder timeoutRead(Duration newVal) {
            requireNonNull(newVal);
            return new DefaultBuilder(this, s -> s.timeoutRead = newVal);
        }
        
        @Override
        public Builder timeoutResponse(Duration newVal) {
            requireNonNull(newVal);
            return new DefaultBuilder(this, s -> s.timeoutResponse = newVal);
        }
        
        @Override
        public Builder timeoutWrite(Duration newVal) {
            requireNonNull(newVal);
            return new DefaultBuilder(this, s -> s.timeoutWrite = newVal);
        }
        
        @Override
        public Builder timeoutFileLock(Duration newVal) {
            requireNonNull(newVal);
            return new DefaultBuilder(this, s -> s.timeoutFileLock = newVal);
        }
        
        @Override
        public Builder implementMissingOptions(boolean newVal) {
            return new DefaultBuilder(this, s -> s.implementMissingOptions = newVal);
        }
        
        @Override
        public Config build() {
            return new DefaultConfig(this, constructState(MutableState::new));
        }
    }
}