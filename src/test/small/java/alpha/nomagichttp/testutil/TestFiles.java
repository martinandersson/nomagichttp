package alpha.nomagichttp.testutil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static alpha.nomagichttp.util.ByteBuffers.asArray;

/**
 * Utils for files.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class TestFiles {
    /**
     * Creates a temp file and writes the given content into the file.<p>
     * 
     * The given bytebuffer's position remains unchanged.
     * 
     * @param content of file
     * @return the file
     * @throws IOException on I/O error
     */
    public static Path writeTempFile(ByteBuffer content) throws IOException {
        var file = Files.createTempDirectory("nomagic").resolve("test");
        Files.write(file, asArray(content));
        return file;
    }
}