package org.example;

import java.io.File;
import java.io.IOException;

class TailReaderTest {

    @org.junit.jupiter.api.Test
    void readLastNLines() throws IOException {
        System.out.println(TailReader.readLastNLines(new File("C:\\Users\\libo1\\cp-scheduling-test.log"), 1000));
    }
}