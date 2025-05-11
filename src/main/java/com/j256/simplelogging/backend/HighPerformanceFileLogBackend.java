package com.j256.simplelogging.backend;

import com.j256.simplelogging.Level;
import com.j256.simplelogging.LogBackend;
import com.j256.simplelogging.event.LogEvent;
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

/**
 * 高性能文件日志后端实现，使用异步队列和消费者线程处理日志事件。
 */
public class HighPerformanceFileLogBackend implements LogBackend, java.io.Closeable {
    private final String loggerName;
    private final ConcurrentLinkedQueue<LogEvent> logEventQueue;
    private final ExecutorService logExecutorService;
    private final LogEventConsumer logEventConsumer;
    private Writer logWriter; // 用于写入日志
    private String logFilePath = "hplogger-output.log"; // 默认日志文件路径，后续会改为可配置

    public HighPerformanceFileLogBackend(String classLabel) {
        this.loggerName = classLabel;
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
        this.logEventConsumer = new LogEventConsumer(this.logEventQueue, this.logWriter);
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
        private volatile boolean running = true;

        public LogEventConsumer(ConcurrentLinkedQueue<LogEvent> eventQueue, Writer writer) {
            this.eventQueue = eventQueue;
            this.writer = writer;
        }

        public void stopProcessing() {
            this.running = false;
        }

        @Override
        public void run() {
            try {
                System.out.println(Thread.currentThread().getName() + " starting to consume log events.");
                while (this.running || !this.eventQueue.isEmpty()) {
                    LogEvent event = this.eventQueue.poll();
                    if (event != null) {
                        if (this.writer != null) { // 检查 writer 是否有效
                            try {
                                // 1. 格式化时间戳
                                String formattedTimestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS")
                                        .format(new java.util.Date(event.getTimestamp()));

                                // 2. 获取日志代码
                                String logCode = (event.getLogCode() == null) ? "NO_CODE" : event.getLogCode();

                                // 3. 获取日志消息
                                String message = event.getMessage();

                                // 4. 获取异常信息
                                String throwableInfo = "";
                                if (event.getThrowable() != null) {
                                    throwableInfo = " :: " + event.getThrowable().getClass().getSimpleName();
                                    if (event.getThrowable().getMessage() != null) {
                                        throwableInfo += " - " + event.getThrowable().getMessage();
                                    }
                                }

                                // 5. 组装最终的日志行
                                String lineToLog = String.format("%s,%s,%s%s%n",
                                        formattedTimestamp,
                                        logCode,
                                        message,
                                        throwableInfo
                                );

                                this.writer.write(lineToLog);
                            } catch (IOException e) {
                                System.err.println(Thread.currentThread().getName() +
                                        " consumer thread failed to write log event to file: " + e.getMessage());
                            }
                        } else {
                            System.err.println(Thread.currentThread().getName() +
                                    " consumer thread: logWriter is null, cannot write to file. Event: " + event.getMessage());
                        }
                    } else if (this.running) {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            this.running = false;
                            Thread.currentThread().interrupt();
                            System.out.println(Thread.currentThread().getName() 
                                    + " consumer thread interrupted during sleep, stopping.");
                            break;
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