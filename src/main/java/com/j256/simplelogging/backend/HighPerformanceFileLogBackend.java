package com.j256.simplelogging.backend;

import com.j256.simplelogging.Level;
import com.j256.simplelogging.LogBackend;
import com.j256.simplelogging.LogBackendFactory;
import com.j256.simplelogging.LoggerConstants;
import com.j256.simplelogging.PropertyUtils;
import com.j256.simplelogging.event.LogEvent;
import com.j256.simplelogging.backend.NullLogBackend.NullLogBackendFactory;
import java.io.IOException;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.ArrayList;

/**
 * 高性能文件日志后端实现，使用异步队列和消费者线程处理日志事件。
 */
public class HighPerformanceFileLogBackend implements LogBackend, java.io.Closeable {
    private final String loggerName;
    private final ConcurrentLinkedQueue<LogEvent> logEventQueue;
    private final ExecutorService logExecutorService;
    private final LogEventConsumer logEventConsumer;
    private Writer logWriter; // 用于写入日志
    private String logFilePath; // 日志文件路径，从配置读取
    private final int configuredBatchSize; // 批量大小，从配置读取
    private final long configuredFlushIntervalMillis; // 刷新间隔，从配置读取

    public HighPerformanceFileLogBackend(String classLabel) {
        this.loggerName = classLabel;

        // Get a default LogBackendFactory for PropertyUtils to log warnings, if any.
        // Using NullLogBackendFactory to avoid cycles if this backend itself is being configured.
        LogBackendFactory propertyUtilsLoggingFactory = NullLogBackendFactory.getSingleton();

        // 从配置中读取参数
        this.logFilePath = PropertyUtils.readStringProperty(
                LoggerConstants.HIGH_PERF_LOG_FILE_PROPERTY,
                LoggerConstants.HIGH_PERF_LOG_FILE_DEFAULT,
                propertyUtilsLoggingFactory);

        this.configuredBatchSize = PropertyUtils.readIntProperty(
                LoggerConstants.HIGH_PERF_BATCH_SIZE_PROPERTY,
                LoggerConstants.HIGH_PERF_BATCH_SIZE_DEFAULT,
                propertyUtilsLoggingFactory);

        this.configuredFlushIntervalMillis = PropertyUtils.readLongProperty(
                LoggerConstants.HIGH_PERF_FLUSH_INTERVAL_PROPERTY,
                LoggerConstants.HIGH_PERF_FLUSH_INTERVAL_DEFAULT,
                propertyUtilsLoggingFactory);

        openFile(); // 初始化 logWriter
        this.logEventQueue = new ConcurrentLinkedQueue<>();
        
        // 创建一个守护线程工厂，这样消费者线程不会阻止JVM退出
        ThreadFactory daemonThreadFactory = new ThreadFactory() {
            private final AtomicInteger threadNumber = new AtomicInteger(1);
            private final String namePrefix = "hplogger-consumer-" + classLabel + "-";

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, namePrefix + threadNumber.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
        this.logExecutorService = Executors.newSingleThreadExecutor(daemonThreadFactory);
        
        // 创建并启动消费者
        this.logEventConsumer = new LogEventConsumer(this.logEventQueue, this.logWriter, this.configuredBatchSize, this.configuredFlushIntervalMillis);
        this.logExecutorService.submit(this.logEventConsumer);

        // 添加关闭钩子
        final String backendLoggerName = this.loggerName;
        Thread shutdownHook = new Thread(() -> {
            try {
                this.close();
            } catch (java.io.IOException e) {
                System.err.println("HPLB: Error closing HighPerformanceFileLogBackend during JVM shutdown for "
                        + backendLoggerName + ": " + e.getMessage());
            }
        }, "hplogger-shutdown-hook-" + backendLoggerName);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    @Override
    public void log(Level level, String message) {
        long timestamp = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();
        LogEvent event = new LogEvent(timestamp, level, message, null, null, threadName, this.loggerName, null);
        this.logEventQueue.offer(event);
    }

    @Override
    public void log(Level level, String message, Throwable throwable) {
        long timestamp = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();
        LogEvent event = new LogEvent(timestamp, level, message, null, throwable, threadName, this.loggerName, null);
        this.logEventQueue.offer(event);
    }

    @Override
    public boolean isLevelEnabled(Level level) {
        return true; // 暂时总是返回 true，后续可以根据配置调整
    }

    @Override
    public void close() throws IOException {
        if (this.logEventConsumer != null) {
            this.logEventConsumer.stopProcessing();
        }

        if (this.logExecutorService != null && !this.logExecutorService.isShutdown()) {
            this.logExecutorService.shutdown();
            try {
                if (!this.logExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    System.err.println("HPLB: Consumer executor service did not terminate gracefully in 5 seconds for " 
                            + this.loggerName + ". Forcing shutdown.");
                    this.logExecutorService.shutdownNow();
                    if (!this.logExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
                        System.err.println("HPLB: Consumer executor service did not terminate even after shutdownNow() for " 
                                + this.loggerName);
                    }
                }
            } catch (InterruptedException ie) {
                System.err.println("HPLB: Shutdown of consumer executor service was interrupted for " + this.loggerName);
                this.logExecutorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (this.logWriter != null) {
            try {
                // 在关闭 writer 之前，确保所有缓冲的内容都被写出
                this.logWriter.flush();
                this.logWriter.close();
            } catch (IOException e) {
                System.err.println("HPLB: Error closing log file writer for " + this.loggerName + ": " + e.getMessage());
                e.printStackTrace(System.err);
            }
            this.logWriter = null; // 标记为已关闭
        }
    }

    private void openFile() {
        try {
            // 使用 FileOutputStream 并设置为追加模式 (true)
            // 使用 OutputStreamWriter 指定 UTF-8 编码
            // 使用 BufferedWriter 提高写入效率
            FileOutputStream fos = new FileOutputStream(this.logFilePath, true);
            OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            this.logWriter = new BufferedWriter(osw);
            System.out.println("HPLB: Opened log file for appending: " + this.logFilePath + " for logger " + this.loggerName);
        } catch (IOException e) {
            // 如果文件无法打开，日志将无法写入，这是一个严重问题。
            // 暂时打印到标准错误，并禁用进一步的文件写入尝试以避免重复错误。
            System.err.println("HPLB: CRITICAL - Failed to open log file '" + this.logFilePath + "' for logger " + this.loggerName + ". Logging to this file will be disabled. Error: " + e.getMessage());
            e.printStackTrace(System.err);
            this.logWriter = null; // 确保 logWriter 为 null，以便后续检查
        }
    }

    private static class LogEventConsumer implements Runnable {
        private final ConcurrentLinkedQueue<LogEvent> eventQueue;
        private final Writer writer;
        private final int batchSize;
        private final long flushIntervalMillis;
        private volatile boolean running = true;
        private long lastFlushTimeMillis;

        public LogEventConsumer(ConcurrentLinkedQueue<LogEvent> eventQueue, Writer writer, int batchSize, long flushIntervalMillis) {
            this.eventQueue = eventQueue;
            this.writer = writer;
            this.batchSize = batchSize > 0 ? batchSize : 100; // Default to 100 if batchSize is not positive
            this.flushIntervalMillis = flushIntervalMillis > 0 ? flushIntervalMillis : 1000; // Default to 1000ms if not positive
            this.lastFlushTimeMillis = System.currentTimeMillis(); // Initialize last flush time
        }

        public void stopProcessing() {
            this.running = false;
        }

        @Override
        public void run() {
            try {
                System.out.println(Thread.currentThread().getName() + " starting to consume log events.");
                List<LogEvent> batch = new ArrayList<>(this.batchSize);
                
                while (this.running || !this.eventQueue.isEmpty() || !batch.isEmpty()) {
                    LogEvent event = this.eventQueue.poll();
                    if (event != null) {
                        batch.add(event);
                    }

                    long currentTimeMillis = System.currentTimeMillis();
                    boolean batchFull = batch.size() >= this.batchSize;
                    boolean flushIntervalReached = !batch.isEmpty() && (currentTimeMillis - this.lastFlushTimeMillis >= this.flushIntervalMillis);
                    boolean shuttingDownWithData = !this.running && this.eventQueue.isEmpty() && !batch.isEmpty();

                    if (batchFull || flushIntervalReached || shuttingDownWithData) {
                        if (!batch.isEmpty()) {
                            // --- BEGIN processBatch logic (inlined) ---
                            if (this.writer != null) {
                                try {
                                    StringBuilder batchContentBuilder = new StringBuilder(batch.size() * 100); // Initial capacity estimate
                                    for (LogEvent logEv : batch) {
                                        String formattedTimestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS")
                                                                .format(new java.util.Date(logEv.getTimestamp()));
                                        String logCode = (logEv.getLogCode() == null) ? "NO_CODE" : logEv.getLogCode();
                                        String message = logEv.getMessage();
                                        String throwableInfo = "";
                                        if (logEv.getThrowable() != null) {
                                            throwableInfo = " :: " + logEv.getThrowable().getClass().getSimpleName();
                                            if (logEv.getThrowable().getMessage() != null) {
                                                throwableInfo += " - " + logEv.getThrowable().getMessage();
                                            }
                                        }
                                        batchContentBuilder.append(String.format("%s,%s,%s%s%n",
                                                formattedTimestamp, logCode, message, throwableInfo));
                                    }
                                    this.writer.write(batchContentBuilder.toString());
                                    this.writer.flush(); // Flush after writing a batch
                                } catch (IOException e) {
                                    System.err.println(Thread.currentThread().getName() +
                                            " consumer thread failed to write log batch to file: " + e.getMessage());
                                } finally {
                                    batch.clear(); // Prepare for next batch
                                    this.lastFlushTimeMillis = System.currentTimeMillis(); // Update last flush time
                                }
                            } else {
                                System.err.println(Thread.currentThread().getName() +
                                        " consumer thread: logWriter is null, cannot write batch to file. Batch size: " + batch.size() + ". Discarding batch.");
                                batch.clear(); // Discard batch if writer is null
                                this.lastFlushTimeMillis = System.currentTimeMillis();
                            }
                            // --- END processBatch logic ---
                        }
                    }

                    // If queue is empty, not shutting down, and batch is empty (or just flushed), then sleep.
                    if (event == null && this.running && batch.isEmpty()) {
                        try {
                            // Sleep for a fraction of the flush interval, or a minimum, to be responsive
                            long sleepTime = Math.min(100L, this.flushIntervalMillis / 10);
                            if (sleepTime <= 0) sleepTime = 10L; // Ensure positive sleep time
                            Thread.sleep(sleepTime);
                        } catch (InterruptedException e) {
                            this.running = false; // Stop running if interrupted
                            Thread.currentThread().interrupt(); // Preserve interrupt status
                            System.out.println(Thread.currentThread().getName() +
                                    " consumer thread interrupted during sleep, preparing to stop.");
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println(Thread.currentThread().getName() 
                        + " consumer thread encountered an unexpected error: " + e.getMessage());
                e.printStackTrace(System.err);
            } finally {
                System.out.println(Thread.currentThread().getName() + " consumer thread finished processing loop.");
            }
        }
    }
} 