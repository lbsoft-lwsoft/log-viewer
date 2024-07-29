package lwsoft.club.log.reader;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TailReaderTest {

    @Test
    void readLastNLines() throws IOException, URISyntaxException {
        final var resource = TailReaderTest.class.getClassLoader().getResource("test.log");
        assertNotNull(resource, "Test log file not found");
        final var file = new File(resource.toURI());
        final var str = TailReader.readLastNLines(file, 2);
        assertEquals("6\r\n7", str);
    }
}