/**
 * Now this has a comment.
 */
module alpha.nomagichttp.testutil {
    requires transitive alpha.nomagichttp;
    requires transitive org.assertj.core;
    requires transitive java.logging;
    requires transitive org.junit.jupiter.api;
    
    requires org.mockito;
    
    // Clients
    requires io.netty.buffer;
    requires io.netty.codec.http;
    requires java.management;
    requires java.net.http;
    requires kotlin.stdlib;
    requires okhttp3;
    requires okio;
    requires org.apache.httpcomponents.client5.httpclient5;
    requires org.apache.httpcomponents.core5.httpcore5;
    requires org.eclipse.jetty.client;
    requires org.reactivestreams;
    requires reactor.core;
    requires reactor.netty.core;
    requires reactor.netty.http;
    
    exports alpha.nomagichttp.testutil;
    
    exports alpha.nomagichttp.testutil.functional
         to alpha.nomagichttp.core.mediumtest,
            alpha.nomagichttp.core.largetest;
    
    opens alpha.nomagichttp.testutil.functional to org.junit.platform.commons;
}
