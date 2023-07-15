/**
 * Home of the library-provided server implementation.<p>
 * 
 * The only public type in this package is {@link
 * alpha.nomagichttp.core.DefaultServer}, which is used by the {@link
 * alpha.nomagichttp.HttpServer} interface as the default implementation. All
 * other types in this package can therefore be regarded as an implementation
 * detail.<p>
 * 
 * Similar to classes found in other packages, implementations of public
 * interfaces provided by this package also use the "Default" name-prefix. For
 * example, {@code DefaultChannelWriter} implements {@code ChannelWriter}.<p>
 * 
 * Some interfaces, however, may have many implementations, and some interfaces
 * may only indicate a trait of an implementation, both of which would be cases
 * where the "Default" name-prefix is not used. For example, what would a
 * "DefaultByteBufferIterable" do? Generate random bytebuffers? Nor is
 * {@code ChannelReader} a public interface, it's simply the class we use when
 * reading from a channel.<p>
 * 
 * Unless documented differently, all methods within this package — whether
 * public or private — expect to be given non-null arguments and will return
 * non-null results.
 */
package alpha.nomagichttp.internal;