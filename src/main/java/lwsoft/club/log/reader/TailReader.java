package lwsoft.club.log.reader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

public class TailReader {

    private static final int BUFFER_SIZE = 128;

    public static String readLastNLines(File file, int n) throws IOException {
        if (n == 0) {
            return "";
        }
        try (var raf = new RandomAccessFile(file, "r"); var out = new ByteArrayOutputStream()) {
            int index;
            int lineCount = 0;
            long position = file.length();
            byte[] buf = new byte[BUFFER_SIZE];
            do {
                position = Math.max(position - BUFFER_SIZE, 0);
                raf.seek(position);
                index = raf.read(buf);
                if (index != -1) {
                    for (int i = index - 1; i >= 0 ; i--) {
                        if (buf[i] == '\n') {
                            lineCount++;
                            if (lineCount >= n) {
                                break;
                            }
                        }
                        out.write(buf[i]);
                    }
                }
            } while (position != 0 && lineCount < n);
            final var data = out.toByteArray();
            reverseArray(data);
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    public static void reverseArray(byte[] array) {
        int left = 0;
        int right = array.length - 1;
        while (left < right) {
            byte temp = array[left];
            array[left] = array[right];
            array[right] = temp;
            left++;
            right--;
        }
    }

}
