package alpha.nomagichttp.testutil;

import alpha.nomagichttp.Config;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * A configuration object that delegates all calls to the {@code DEFAULT},
 * except for a specific call {@code n} which returns a different value.<p>
 * 
 * Using this class requires knowledge how the server's source code runs,
 * particularly, which poll count of a certain configuration is desired to
 * inject with a hacked test-only value.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class TestConfig implements Config {
    /**
     * Intercept call {@code n} and return {@code injectedVal}.
     * 
     * @param n call count target (starts at 1)
     * @param injectedVal new value to return
     * @return a sneaky config object
     */
    public static TestConfig timeoutIdleConnection(int n, Duration injectedVal) {
        return new TestConfig(Method.timeoutIdleConnection, n, injectedVal);
    }
    
    private enum Method {
        maxRequestHeadSize           (Config::maxRequestHeadSize),
        maxRequestTrailersSize       (Config::maxRequestTrailersSize),
        maxUnsuccessfulResponses     (Config::maxUnsuccessfulResponses),
        maxErrorRecoveryAttempts     (Config::maxErrorRecoveryAttempts),
        threadPoolSize               (Config::threadPoolSize),
        rejectClientsUsingHTTP1_0    (Config::rejectClientsUsingHTTP1_0),
        ignoreRejectedInformational  (Config::ignoreRejectedInformational),
        immediatelyContinueExpect100 (Config::immediatelyContinueExpect100),
        timeoutIdleConnection        (Config::timeoutIdleConnection),
        implementMissingOptions      (Config::implementMissingOptions);
        
        private final Function<Config, Object> get;
        
        Method(Function<Config, Object> getter) {
            get = getter;
        }
        
        <T> T get(Config from) {
            @SuppressWarnings("unchecked")
            T t = (T) get.apply(from);
            return t;
        }
    }
    
    private final Method method;
    private final AtomicInteger call;
    private final int target;
    private final Object val;
    
    private TestConfig(Method interceptedMethod, int n, Object interceptedVal) {
        this.method = interceptedMethod;
        this.call   = new AtomicInteger();
        this.target = n;
        this.val    = interceptedVal;
    }
    
    @Override
    public int maxRequestHeadSize() {
        return get(Method.maxRequestHeadSize);
    }
    
    @Override
    public int maxRequestTrailersSize() {
        return get(Method.maxRequestTrailersSize);
    }
    
    @Override
    public int maxUnsuccessfulResponses() {
        return get(Method.maxUnsuccessfulResponses);
    }
    
    @Override
    public int maxErrorRecoveryAttempts() {
        return get(Method.maxErrorRecoveryAttempts);
    }
    
    @Override
    public int threadPoolSize() {
        return get(Method.threadPoolSize);
    }
    
    @Override
    public boolean rejectClientsUsingHTTP1_0() {
        return get(Method.rejectClientsUsingHTTP1_0);
    }
    
    @Override
    public boolean ignoreRejectedInformational() {
        return get(Method.ignoreRejectedInformational);
    }
    
    @Override
    public boolean immediatelyContinueExpect100() {
        return get(Method.immediatelyContinueExpect100);
    }
    
    @Override
    public Duration timeoutIdleConnection() {
        return get(Method.timeoutIdleConnection);
    }
    
    @Override
    public boolean implementMissingOptions() {
        return get(Method.implementMissingOptions);
    }
    
    private <T> T get(Method m) {
        if (this.method == m && call.incrementAndGet() == target) {
            @SuppressWarnings("unchecked")
            T t = (T) val;
            return t;
        }
        return m.get(DEFAULT);
    }
    
    @Override
    public Builder toBuilder() {
        throw new UnsupportedOperationException();
    }
}
