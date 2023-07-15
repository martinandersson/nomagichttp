/**
 * Comment.
 */
module alpha.nomagichttp.core.largetest {
    requires alpha.nomagichttp.testutil;
    requires org.junit.jupiter.params;
    
    opens alpha.nomagichttp.core.largetest to org.junit.platform.commons;
    opens alpha.nomagichttp.core.largetest.util to org.junit.platform.commons;
}
