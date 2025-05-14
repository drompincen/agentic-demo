package com.example.agency.bus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class Source {
    private static final Logger log = LoggerFactory.getLogger(Source.class);
    private static final Path STATE_BASE_DIR = Paths.get("data", "bus_state");

    // ... (readOffset and writeOffset helpers remain the same)
    private static long readOffset(Path offsetFile) {
        if (Files.exists(offsetFile)) {
            try {
                String content = Files.readString(offsetFile, StandardCharsets.UTF_8);
                return Long.parseLong(content.trim());
            } catch (IOException | NumberFormatException e) {
                log.error("Error reading offset file {} or parsing its content. Defaulting to 0.", offsetFile, e);
            }
        }
        return 0L;
    }

    private static void writeOffset(Path offsetFile, long offset) {
        try {
            Files.createDirectories(offsetFile.getParent());
            Files.writeString(offsetFile, String.valueOf(offset), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.error("Error writing offset file {}. Offset persistence might fail.", offsetFile, e);
        }
    }


    private static class TaskDispatcher {
        // ... (maps remain the same) ...
        private static final Map<String, List<Consumer<String>>> subscribers = new ConcurrentHashMap<>();
        private static final Map<String, Path> queueFilePaths = new ConcurrentHashMap<>();
        private static final Map<String, Path> queueOffsetFiles = new ConcurrentHashMap<>();
        private static final Map<String, Long> filePositions = new ConcurrentHashMap<>();

        private static final ScheduledExecutorService pollerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TaskDispatcher-Poller");
            // t.setDaemon(true); // REMOVE THIS or set to false to keep app alive
            log.info("Creating TaskDispatcher-Poller thread (non-daemon by default unless setDaemon(true) is called)");
            return t;
        });
        private static final ExecutorService handlerExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "TaskDispatcher-Handler");
            t.setDaemon(true); // Handlers can often be daemons if pollers are not
            return t;
        });

        static {
            // ... (static block content remains the same) ...
            try {
                Files.createDirectories(STATE_BASE_DIR.resolve("tasks"));
            } catch (IOException e) {
                log.error("Could not create base state directory for TaskDispatcher: {}", STATE_BASE_DIR.resolve("tasks"), e);
            }
            pollerExecutor.scheduleAtFixedRate(TaskDispatcher::pollAllQueues, 1, 200, TimeUnit.MILLISECONDS);
            log.info("TaskDispatcher poller started.");
        }
        // ... (registerQueue, subscribe, publish, pollAllQueues methods remain the same) ...
        public static synchronized void registerQueue(String queueName) {
            Path queueFilePath = Paths.get("data", "tasks", queueName + ".jsonl");
            Path offsetFilePath = STATE_BASE_DIR.resolve("tasks").resolve(queueName + ".offset");

            queueFilePaths.putIfAbsent(queueName, queueFilePath);
            queueOffsetFiles.putIfAbsent(queueName, offsetFilePath);
            long initialPosition = readOffset(offsetFilePath);
            filePositions.putIfAbsent(queueName, initialPosition);
            log.info("TaskDispatcher: Queue '{}' registered. Path: {}, OffsetFile: {}, InitialPos: {}",
                    queueName, queueFilePath, offsetFilePath, initialPosition);
        }

        public static synchronized void subscribe(String queueName, Consumer<String> handler) {
            registerQueue(queueName);
            subscribers.computeIfAbsent(queueName, k -> new CopyOnWriteArrayList<>()).add(handler);
            log.info("TaskDispatcher: New subscription for queue '{}'. Handler: {}", queueName, handler.getClass().getName());
        }

        public static void publish(String queueName, String taskPayload) {
            registerQueue(queueName);
            Path path = queueFilePaths.get(queueName);
            if (path == null) {
                log.error("TaskDispatcher: Path not found for queue '{}'. Cannot publish task: {}", queueName, taskPayload);
                return;
            }
            try {
                Files.createDirectories(path.getParent());
                try (BufferedWriter w = Files.newBufferedWriter(path,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    w.write(taskPayload);
                    w.newLine();
                    log.debug("TaskDispatcher: Published to queue file '{}': {}", path, taskPayload);
                }
            } catch (IOException e) {
                log.error("TaskDispatcher: IOException publishing to queue file '{}': {}", path, taskPayload, e);
            }
        }
        private static void pollAllQueues() {
            for (Map.Entry<String, Path> entry : queueFilePaths.entrySet()) {
                String queueName = entry.getKey();
                Path path = entry.getValue();
                Path offsetFile = queueOffsetFiles.get(queueName);
                long currentPos = filePositions.getOrDefault(queueName, 0L);
                long newPos = currentPos;

                if (!Files.exists(path)) continue;
                if (offsetFile == null) {
                    log.error("Offset file path not found for queue {}, skipping poll.", queueName);
                    continue;
                }
                try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
                    if (raf.length() < currentPos) {
                        log.warn("TaskDispatcher: File {} was reset or truncated. Resetting position to 0 and updating offset file.", path);
                        currentPos = 0L;
                        writeOffset(offsetFile, currentPos);
                    }
                    raf.seek(currentPos);
                    String line;
                    List<String> batchOfLines = new ArrayList<>();
                    long batchEndPos = currentPos;

                    while ((line = raf.readLine()) != null) {
                        batchOfLines.add(line);
                        batchEndPos = raf.getFilePointer();
                    }

                    if (!batchOfLines.isEmpty()) {
                        newPos = batchEndPos;
                        List<Consumer<String>> handlers = subscribers.get(queueName);
                        if (handlers != null && !handlers.isEmpty()) {
                            log.debug("TaskDispatcher: Polled {} new task(s) for queue '{}'", batchOfLines.size(), queueName);
                            for (String finalLine : batchOfLines) {
                                for (Consumer<String> handler : handlers) {
                                    handlerExecutor.submit(() -> {
                                        try {
                                            handler.accept(finalLine);
                                        } catch (Exception e) {
                                            log.error("TaskDispatcher: Error in handler for queue '{}', task '{}'", queueName, finalLine, e);
                                        }
                                    });
                                }
                            }
                            filePositions.put(queueName, newPos);
                            writeOffset(offsetFile, newPos);
                            log.debug("TaskDispatcher: Queue '{}' position updated to {} and persisted.", queueName, newPos);
                        } else {
                            //log.warn("TaskDispatcher: Polled {} tasks for queue '{}' but no subscribers found. Tasks will be re-polled.", batchOfLines.size(), queueName);
                            Thread.sleep(10 * 1000);
                        }
                    }
                } catch (FileNotFoundException fnfe) {
                    // file deleted
                } catch (IOException e) {
                    log.error("TaskDispatcher: IOException polling queue file '{}'", path, e);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }

    }

    public static class FileSource {
        // ... (path, offsetFilePath, subs, pos remain same) ...
        private Path path = null;
        private final Path offsetFilePath;
        private final List<Consumer<String>> subs = new CopyOnWriteArrayList<>();
        private long pos = 0;
        private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "FileSource-Poller-" + path.getFileName().toString().replace(".log", ""));
            // t.setDaemon(true); // REMOVE THIS or set to false
            log.info("Creating FileSource-Poller thread for {} (non-daemon by default)", path.getFileName());
            return t;
        });
        private static final ExecutorService handlerExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "FileSource-Handler");
            t.setDaemon(true); // Event handlers can often be daemons
            return t;
        });

        public FileSource(String topic) {
            // ... (constructor logic remains the same, including readOffset)
            this.path = Paths.get("data", "events", topic + ".log");
            this.offsetFilePath = STATE_BASE_DIR.resolve("events").resolve(topic + ".offset");
            try {
                if (!Files.exists(path.getParent())) Files.createDirectories(path.getParent());
                if (!Files.exists(offsetFilePath.getParent())) Files.createDirectories(offsetFilePath.getParent());
            } catch (IOException e) {
                log.error("FileSource: Could not create parent directory for {} or {}", path, offsetFilePath, e);
            }
            this.pos = readOffset(this.offsetFilePath);
            exec.scheduleAtFixedRate(this::poll, 1, 200, TimeUnit.MILLISECONDS);
            log.info("FileSource initialized for topic '{}', polling file: {}. Initial offset: {}", topic, path.toAbsolutePath(), this.pos);
        }
        // ... (publish, subscribe, poll methods remain the same) ...
        public void publish(String msg) {
            try {
                Files.createDirectories(path.getParent());
                try (BufferedWriter w = Files.newBufferedWriter(path,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    w.write(msg); w.newLine();
                    log.debug("FileSource: Published to event file '{}': {}", path.getFileName(), msg);
                }
            } catch (IOException e) {
                log.error("FileSource: IOException publishing to event file '{}': {}", path.getFileName(), msg, e);
            }
        }

        public void subscribe(Consumer<String> handler) {
            subs.add(handler);
            log.info("FileSource: New subscription to topic '{}' using file {}", path.getFileName().toString().replace(".log",""), path.toAbsolutePath());
        }

        private void poll() {
            if (!Files.exists(path)) return;

            long currentFileLength = 0;
            try {
                currentFileLength = Files.size(path);
            } catch (IOException e) {
                log.error("FileSource: Could not get size of {}, skipping poll.", path, e);
                return;
            }

            if (currentFileLength < pos) {
                log.warn("FileSource: File {} was reset or truncated (current_len: {}, saved_pos: {}). Resetting position to 0.", path, currentFileLength, pos);
                pos = 0L;
                writeOffset(offsetFilePath, pos);
            }

            if (currentFileLength == pos && pos > 0) return; // Only return if pos > 0 to ensure initial poll happens for empty file
            if (currentFileLength == 0 && pos == 0) return; // Empty file, nothing to do


            try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
                raf.seek(pos);
                String line;
                List<String> batchOfLines = new ArrayList<>();
                long batchEndPos = pos;

                while ((line = raf.readLine()) != null) {
                    batchOfLines.add(line);
                    batchEndPos = raf.getFilePointer();
                }

                if (!batchOfLines.isEmpty()) {
                    pos = batchEndPos;
                    log.debug("FileSource: Polled {} new event(s) for topic '{}'. New pos: {}", batchOfLines.size(), path.getFileName().toString().replace(".log",""), pos);
                    for (String finalLine : batchOfLines) {
                        subs.forEach(h -> handlerExecutor.submit(() -> {
                            try {
                                h.accept(finalLine);
                            } catch (Exception e) {
                                log.error("FileSource: Error in handler for topic '{}', event '{}'", path.getFileName().toString().replace(".log",""), finalLine, e);
                            }
                        }));
                    }
                    writeOffset(offsetFilePath, pos);
                }
            } catch (FileNotFoundException fnfe) {
                // ignore
            } catch (IOException e) {
                log.error("FileSource: IOException polling event file '{}'", path, e);
            }
        }
    }

    public static class TaskQueue {
        // ... (TaskQueue methods remain the same, using TaskDispatcher) ...
        private final String queueName;

        public TaskQueue(String agentName) {
            this.queueName = agentName;
            TaskDispatcher.registerQueue(agentName);
            log.debug("TaskQueue facade created for agent: {}", agentName);
        }

        public void enqueue(String task) {
            TaskDispatcher.publish(this.queueName, task);
        }

        public void subscribe(Consumer<String> handler) {
            TaskDispatcher.subscribe(this.queueName, handler);
        }
    }
}