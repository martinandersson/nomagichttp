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
                           maxUnsuccessfulResponses,
                           maxErrorRecoveryAttempts,
                           threadPoolSize;
    private final boolean  rejectClientsUsingHTTP1_0,
                           ignoreRejectedInformational,
                           immediatelyContinueExpect100;
    private final Duration timeoutIdleConnection;
    private final boolean  implementMissingOptions;
    
    DefaultConfig(Builder b, DefaultBuilder.MutableState s) {
        builder                      = b;
        maxRequestHeadSize           = s.maxRequestHeadSize;
        maxUnsuccessfulResponses     = s.maxUnsuccessfulResponses;
        maxErrorRecoveryAttempts     = s.maxErrorRecoveryAttempts;
        threadPoolSize               = s.threadPoolSize;
        rejectClientsUsingHTTP1_0    = s.rejectClientsUsingHTTP1_0;
        ignoreRejectedInformational  = s.ignoreRejectedInformational;
        immediatelyContinueExpect100 = s.immediatelyContinueExpect100;
        timeoutIdleConnection        = s.timeoutIdleConnection;
        implementMissingOptions      = s.implementMissingOptions;
    }
    
    @Override
    public int maxRequestHeadSize() {
        return maxRequestHeadSize;
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
    public Duration timeoutIdleConnection() {
        return timeoutIdleConnection;
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
                     maxUnsuccessfulResponses     = 7,
                     maxErrorRecoveryAttempts     = 5,
                     threadPoolSize               = max(3, getRuntime().availableProcessors());
            boolean  rejectClientsUsingHTTP1_0    = false,
                     ignoreRejectedInformational  = true,
                     immediatelyContinueExpect100 = false;
            Duration timeoutIdleConnection        = ofSeconds(90);
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
        public Builder timeoutIdleConnection(Duration newVal) {
            requireNonNull(newVal);
            return new DefaultBuilder(this, s -> s.timeoutIdleConnection = newVal);
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