package com.j256.simplelogging.backend;

import com.j256.simplelogging.Level;
import com.j256.simplelogging.LogBackend;
import com.j256.simplelogging.event.LogEvent;
import java.io.IOException;
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

    public HighPerformanceFileLogBackend(String classLabel) {
        this.loggerName = classLabel;
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
        this.logEventConsumer = new LogEventConsumer(this.logEventQueue);
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
    }

    private static class LogEventConsumer implements Runnable {
        private final ConcurrentLinkedQueue<LogEvent> eventQueue;
        private volatile boolean running = true;

        public LogEventConsumer(ConcurrentLinkedQueue<LogEvent> eventQueue) {
            this.eventQueue = eventQueue;
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
                        String formattedTimestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS")
                                .format(new java.util.Date(event.getTimestamp()));
                        String throwableInfo = (event.getThrowable() == null) ? "" 
                                : " - EXCEPTION: " + event.getThrowable().getClass().getSimpleName() 
                                + (event.getThrowable().getMessage() == null ? "" 
                                : " (" + event.getThrowable().getMessage() + ")");
                        String logCodeInfo = (event.getLogCode() == null) ? "" 
                                : " CODE: [" + event.getLogCode() + "]";

                        System.out.printf("[CONSUMER] %s [%s] %s %s [%s]%s: %s%s%n",
                                formattedTimestamp,
                                event.getThreadName(),
                                event.getLevel(),
                                event.getLoggerName(),
                                Thread.currentThread().getName(),
                                logCodeInfo,
                                event.getMessage(),
                                throwableInfo
                        );
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