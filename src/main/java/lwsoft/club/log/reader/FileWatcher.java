package lwsoft.club.log.reader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

public class FileWatcher {

    private final static Logger LOGGER = LoggerFactory.getLogger(FileWatcher.class);
    private final Map<File, Thread> fileWatcherThreadMap = new HashMap<>();
    private final Map<File, FileChangeHandler> handlerMap = new HashMap<>();

    public synchronized void addListener(File file, FileChangeHandler handler) throws IOException {
        if (!fileWatcherThreadMap.containsKey(file)) {
            this.fileWatcherThreadMap.put(file, this.createWatchingThread(file));
        }
        this.handlerMap.put(file, handler);
    }

    public void remove(File file) {
        final var thread = this.fileWatcherThreadMap.remove(file);
        if (thread != null) {
            thread.interrupt();
        }
        this.handlerMap.remove(file);
    }

    public boolean contains(File file) {
        return this.fileWatcherThreadMap.containsKey(file);
    }

    private Thread createWatchingThread(File file) {
        final var thread = Thread.ofVirtual().unstarted(() -> {
            LOGGER.info("Starting file watcher thread for {}", file.getAbsolutePath());
            try(final var loader = new FileChangeLoader(file, 1024)) {
                while (!Thread.currentThread().isInterrupted()) {
                    ByteBuffer buffer;
                    while ((buffer = loader.read()) != null) {
                        final var handler = this.handlerMap.get(file);
                        if (handler != null) {
                            try {
                                handler.handle(buffer);
                            } catch (Exception ignored) {}
                        }
                    }
                    Thread.sleep(10);
                }
            } catch (Exception e) {
                LOGGER.error("Error while watching file {}", file.getAbsolutePath(), e);
            } finally {
                LOGGER.info("Stopping file watcher thread for {}", file.getAbsolutePath());
            }
        });
        thread.setName("FileWatcher-%s".formatted(file.getAbsolutePath()));
        thread.start();
        return thread;
    }

    public static class FileChangeLoader implements AutoCloseable {

        private final FileChannel fileChannel;
        private final ByteBuffer buffer;

        public FileChangeLoader(File file, int bufferSize) throws IOException {
            this.buffer = ByteBuffer.allocateDirect(bufferSize);
            this.fileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
            this.fileChannel.position(file.length());
        }

        public ByteBuffer read() throws IOException {
            buffer.clear();
            int bytesRead = fileChannel.read(buffer);
            if (bytesRead <= 0) {
                return null;
            }
            buffer.flip();
            return buffer;
        }

        @Override
        public void close() throws IOException {
            this.fileChannel.close();
        }
    }

    public interface FileChangeHandler {
        void handle(ByteBuffer buffer);
    }
}
