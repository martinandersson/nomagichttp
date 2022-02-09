package alpha.nomagichttp;

import alpha.nomagichttp.util.AbstractImmutableBuilder;

import java.time.Duration;
import java.util.function.Consumer;

import static java.lang.Math.max;
import static java.lang.Runtime.getRuntime;
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
                           maxRequestTrailersSize,
                           maxUnsuccessfulResponses,
                           maxErrorRecoveryAttempts,
                           threadPoolSize;
    private final boolean  rejectClientsUsingHTTP1_0,
                           ignoreRejectedInformational,
                           immediatelyContinueExpect100;
    private final Duration timeoutRead,
                           timeoutResponse,
                           timeoutWrite;
    private final boolean  implementMissingOptions;
    
    DefaultConfig(Builder b, DefaultBuilder.MutableState s) {
        builder                      = b;
        maxRequestHeadSize           = s.maxRequestHeadSize;
        maxRequestTrailersSize       = s.maxRequestTrailersSize;
        maxUnsuccessfulResponses     = s.maxUnsuccessfulResponses;
        maxErrorRecoveryAttempts     = s.maxErrorRecoveryAttempts;
        threadPoolSize               = s.threadPoolSize;
        rejectClientsUsingHTTP1_0    = s.rejectClientsUsingHTTP1_0;
        ignoreRejectedInformational  = s.ignoreRejectedInformational;
        immediatelyContinueExpect100 = s.immediatelyContinueExpect100;
        timeoutRead                  = s.timeoutRead;
        timeoutResponse              = s.timeoutResponse;
        timeoutWrite                 = s.timeoutWrite;
        implementMissingOptions      = s.implementMissingOptions;
    }
    
    @Override
    public int maxRequestHeadSize() {
        return maxRequestHeadSize;
    }
    
    @Override
    public int maxRequestTrailersSize() {
        return maxRequestTrailersSize;
    }
    
    @Override
    public int maxUnsuccessfulResponses() {
        return maxUnsuccessfulResponses;
    }
    
    @Override
    public int maxErrorRecoveryAttempts() {
        return maxErrorRecoveryAttempts;
    }
    
    @Override
    public int threadPoolSize() {
        return threadPoolSize;
    }
    
    @Override
    public boolean rejectClientsUsingHTTP1_0() {
        return rejectClientsUsingHTTP1_0;
    }
    
    @Override
    public boolean ignoreRejectedInformational() {
        return ignoreRejectedInformational;
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
                     maxRequestTrailersSize       = 8_000,
                     maxUnsuccessfulResponses     = 7,
                     maxErrorRecoveryAttempts     = 5,
                     threadPoolSize               = max(3, getRuntime().availableProcessors());
            boolean  rejectClientsUsingHTTP1_0    = false,
                     ignoreRejectedInformational  = true,
                     immediatelyContinueExpect100 = false;
            Duration timeoutRead                  = ofSeconds(90),
                     timeoutResponse              = timeoutRead,
                     timeoutWrite                 = timeoutRead;
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
        public Builder maxUnsuccessfulResponses(int newVal) {
            return new DefaultBuilder(this, s -> s.maxUnsuccessfulResponses = newVal);
        }
        
        @Override
        public Builder maxErrorRecoveryAttempts(int newVal) {
            return new DefaultBuilder(this, s -> s.maxErrorRecoveryAttempts = newVal);
        }
        
        @Override
        public Builder threadPoolSize(int newVal) {
            return new DefaultBuilder(this, s -> s.threadPoolSize = newVal);
        }
        
        @Override
        public Builder rejectClientsUsingHTTP1_0(boolean newVal) {
            return new DefaultBuilder(this, s -> s.rejectClientsUsingHTTP1_0 = newVal);
        }
        
        @Override
        public Builder ignoreRejectedInformational(boolean newVal) {
            return new DefaultBuilder(this, s -> s.ignoreRejectedInformational = newVal);
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
        public Builder implementMissingOptions(boolean newVal) {
            return new DefaultBuilder(this, s -> s.implementMissingOptions = newVal);
        }
        
        @Override
        public Config build() {
            return new DefaultConfig(this, constructState(MutableState::new));
        }
    }
}