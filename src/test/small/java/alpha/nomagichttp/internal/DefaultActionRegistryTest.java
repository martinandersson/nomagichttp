package alpha.nomagichttp.internal;

import alpha.nomagichttp.action.ActionNonUniqueException;
import alpha.nomagichttp.action.AfterAction;
import alpha.nomagichttp.action.BeforeAction;
import alpha.nomagichttp.Chain;
import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import static alpha.nomagichttp.internal.DefaultActionRegistry.Match;
import static alpha.nomagichttp.internal.SkeletonRequestTarget.parse;
import static java.util.Collections.reverseOrder;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Small tests for {@link DefaultActionRegistry}.
 *
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class DefaultActionRegistryTest
{
    /**
     * Constructs a {@code DefaultActionRegistryTest}.
     */
    DefaultActionRegistryTest() {
        // Intentionally empty
    }
    
    private DefaultActionRegistry testee;
    private BeforeAction bef_R, bef_Rx, bef_Rxz, bef_Rxzy;
    private AfterAction aft_R, aft_Rx, aft_Rxz, aft_Rxzy;
    {
        testee  = new DefaultActionRegistry(null);
        
        // Salt the register with some dummies
        bef_R = beforeDummy("bef_R");
        testee.before("/", bef_R);
        bef_Rx = beforeDummy("bef_Rx");
        testee.before("/x", bef_Rx);
        bef_Rxz = beforeDummy("bef_Rxz");
        testee.before("/x/z", bef_Rxz);
        bef_Rxzy = beforeDummy("bef_Rxzy");
        testee.before("/x/z/y", bef_Rxzy);
        
        aft_R = afterDummy("aft_R");
        testee.after("/", aft_R);
        aft_Rx = afterDummy("aft_Rx");
        testee.after("/x", aft_Rx);
        aft_Rxz = afterDummy("aft_Rxz");
        testee.after("/x/z", aft_Rxz);
        aft_Rxzy = afterDummy("aft_Rxzy");
        testee.after("/x/z/y", aft_Rxzy);
    }
    
    @Test
    void matchRoot() {
        lookupBefore("/").expect(bef_R).run();
    }
    
    /**
     * Action registered: /user
     * (exactly one segment specified)
     * 
     * Request path:
     * /user                match
     * /                    no match
     * /foo                 no match
     * /user/foo            no match
     */
    @Test
    void javadoc_ex1() {
        var action = beforeDummy("action");
        testee.before("/user", action);
        lookupBefore("/user").expect(action).run();
    }
    
    /**
     * Action registered: /:
     * (exactly one segment, not specified what)
     * 
     * Request path:
     * /user                match
     * /foo                 match
     * /                    no match
     * /user/foo            no match
     * /foo/user            no match
     */
    @Test
    void javadoc_ex2() {
        var action = beforeDummy("action");
        testee.before("/:", action);
        lookupBefore("/user").expect(action).hasParam("", "user").run();
        lookupBefore("/foo").expect(action).hasParam("", "foo").run();
    }
    
    /**
     * Action registered: /user/*
     * (first segment specified, followed by anything)
     * 
     * Request path:
     * /user                match
     * /user/foo            match
     * /user/foo/bar        match
     * /foo                 no match
     */
    @Test
    void javadoc_ex3() {
        var action = beforeDummy("action");
        testee.before("/user/*", action);
        
        lookupBefore("/user").expect(action).hasParam("", "/").run();
        lookupBefore("/user/foo").expect(action).hasParam("", "/foo").run();
        lookupBefore("/user/foo/bar").expect(action).hasParam("", "/foo/bar").run();
    }
    
    /**
     * Request path: /
     * 
     * Matched order:
     * /*
     * /
     */
    @Test
    void javadoc_ex4() {
        var action = beforeDummy("action");
        testee.before("/*", action);
        
        // We added R first, but action is more generic
        lookupBefore("/").expect(action).hasParam("", "/")
                   .expect(bef_R)
                   .run();
    }
    
    /**
     * Request path: /foo/bar
     * 
     * Before-action execution order:
     * /*
     * /:/bar
     * /foo/*
     * /foo/:
     * /foo/bar (added first)
     * /foo/bar (added last)
     */
    @Test
    void javadoc_ex5() {
        BeforeAction act1 = beforeDummy("act1"),
                     act2 = beforeDummy("act2");
        
        of("/*", "/:/bar", "/foo/*", "/foo/:", "/foo/bar")
                // Because why not
                .sorted(reverseOrder())
                .forEach(p -> testee.before(p, act1));
        
        testee.before("/foo/bar", act2);
        
        lookupBefore("/foo/bar")
            .expect(act1).hasParam("", "/foo/bar")
            .expect(act1).hasParam("", "foo")
            .expect(act1).hasParam("", "/bar")
            .expect(act1).hasParam("", "bar")
            .expect(act1)
            .expect(act2)
            .run();
    }
    
    /**
     * Request path: /
     * 
     * After-action execution order:
     * /
     * /*
     */
    @Test
    void javadoc_ex6() {
        var action = afterDummy("action");
        testee.after("/*", action);
        
        // We added R first, but action is more generic and falls back
        lookupAfter("/")
                .expect(aft_R).expect(action).hasParam("", "/")
                .run();
    }
    
    /**
     * Request path: /
     * 
     * Request path: /foo/bar
     * 
     * After-action execution order:
     * /foo/bar (added first)
     * /foo/bar (added last)
     * /foo/:
     * /foo/*
     * /:/bar
     * /*
     */
    @Test
    void javadoc_ex7() {
        AfterAction act1 = afterDummy("act1"),
                    act2 = afterDummy("act2");
        
        of("/*", "/:/bar", "/foo/*", "/foo/:", "/foo/bar")
                // Because why not
                .sorted(reverseOrder())
                .forEach(p -> testee.after(p, act1));
        
        testee.after("/foo/bar", act2);
        
        lookupAfter("/foo/bar")
                .expect(act1)
                .expect(act2)
                .expect(act1).hasParam("", "bar")
                .expect(act1).hasParam("", "/bar")
                .expect(act1).hasParam("", "foo")
                .expect(act1).hasParam("", "/foo/bar")
                .run();
    }
    
    @Test
    void ActionNonUniqueException() {
        var dup = beforeDummy("dup");
        assertThatThrownBy(() -> testee.before("/", dup, dup))
                .isExactlyInstanceOf(ActionNonUniqueException.class)
                .hasMessage("Already added: dup");
    }
    
    private static BeforeAction beforeDummy(String name) {
        return new BeforeAction() {
            @Override
            public void accept(Request request, ClientChannel channel, Chain chain) {
                // Empty
            }
            
            @Override
            public String toString() {
                return name;
            }
        };
    }
    
    private static AfterAction afterDummy(String name) {
        return new AfterAction() {
            @Override
            public CompletionStage<Response> apply(Request req, Response rsp) {
                return rsp.completedStage();
            }
            
            @Override
            public String toString() {
                return name;
            }
        };
    }
    
    RunSpec<BeforeAction> lookupBefore(String pattern) {
        return new RunSpec<>(testee::lookupBefore, pattern);
    }
    
    RunSpec<AfterAction> lookupAfter(String pattern) {
        return new RunSpec<>(testee::lookupAfter, pattern);
    }
    
    private static final class RunSpec<A> {
        // Contained by root only; how to execute the testee
        private Function<SkeletonRequestTarget, List<Match<A>>> method;
        private String pattern;
        // Each node chained after root will contain ordered expectations
        private RunSpec<A> prev;
        private A expected;
        private Map<String, String> paramsRaw, paramsDec;
        
        // Root
        RunSpec(Function<SkeletonRequestTarget, List<Match<A>>> method, String pattern) {
            this.method = method;
            this.pattern = pattern;
        }
        
        // Tail
        private RunSpec(RunSpec<A> prev, A expected) {
            this.prev = prev;
            this.expected = expected;
        }
        
        RunSpec<A> expect(A matched) {
            return new RunSpec<>(this, matched);
        }
        
        RunSpec<A> hasParam(String key, String vRaw) {
            return hasParam(key, vRaw, vRaw);
        }
        
        RunSpec<A> hasParam(String key, String vRaw, String vDec) {
            paramsRaw = put(key, vRaw, paramsRaw);
            paramsDec = put(key, vDec, paramsDec);
            return this;
        }
        
        private Map<String, String> put(String k, String v, Map<String, String> map) {
            if (map == null) {
                map = new HashMap<>();
            }
            map.put(k, v);
            return map;
        }
        
        void run() {
            Deque<RunSpec<A>>  expOrder = buildExpectationChain();
            List<TestMatch<A>> actual   = executeLookup(expOrder.peek().prev),
                               expected = convertSpecs(expOrder);
            assertThat(actual).containsExactlyElementsOf(expected);
        }
        
        private Deque<RunSpec<A>> buildExpectationChain() {
            var expOrder = new ArrayDeque<RunSpec<A>>();
            RunSpec<A> curr;
            for (curr = this; curr.pattern == null; curr = curr.prev) {
                expOrder.addFirst(curr);
            }
            return expOrder;
        }
        
        private static <A> List<TestMatch<A>> executeLookup(RunSpec<A> root) {
            var pathSegments = parse(root.pattern);
            return root.method.apply(pathSegments).stream().map(act -> {
                        var actParams = new RequestTarget(pathSegments, act.segments());
                        return new TestMatch<>(
                                act.action(),
                                actParams.pathParamRawMap(),
                                actParams.pathParamMap());})
                    .collect(toList());
        }
        
        private static <A> List<TestMatch<A>> convertSpecs(Collection<RunSpec<A>> specs) {
            return specs.stream()
                        .map(exp -> new TestMatch<>(exp.expected, exp.paramsRaw, exp.paramsDec))
                        .collect(toList());
        }
        
        // With and equals() comparing path parameters
        private static class TestMatch<A> implements Match<A> {
            private final A action;
            private final Map<String, String> paramsRaw, paramsDec;
            
            TestMatch(A action, Map<String, String> paramsRaw, Map<String, String> paramsDec) {
                this.action = action;
                this.paramsRaw = paramsRaw == null ? Map.of() : paramsRaw;
                this.paramsDec = paramsDec == null ? Map.of() : paramsDec;
            }
            
            @Override
            public A action() {
                throw new UnsupportedOperationException();
            }
            
            @Override
            public Iterable<String> segments() {
                throw new UnsupportedOperationException();
            }
            
            @Override
            public int hashCode() {
                throw new UnsupportedOperationException();
            }
            
            @Override
            public boolean equals(Object obj) {
                assert obj instanceof TestMatch;
                @SuppressWarnings("rawtypes")
                var that = (TestMatch) obj;
                return Objects.equals(this.action, that.action)   &&
                        Objects.equals(this.paramsRaw, that.paramsRaw) &&
                        Objects.equals(this.paramsDec, that.paramsDec);
            }
        }
    }
}