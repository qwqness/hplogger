package com.j256.simplelogging.benchmarks;

import com.j256.simplelogging.Logger;
import com.j256.simplelogging.LoggerFactory;
import com.j256.simplelogging.LogBackendFactory;
import com.j256.simplelogging.backend.HighPerformanceFileLogBackendFactory;
import com.j256.simplelogging.PropertyUtils;
import com.j256.simplelogging.LoggerConstants;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.util.concurrent.TimeUnit;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.Random;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class DirectBenchmark {
    
    private Logger logger;
    private static final String LOG_FILE_PATH = "hplogger-output.log";
    private ScheduledExecutorService executorService;
    private Random random;
    private static final String[] LOG_LEVELS = {"TRACE", "DEBUG", "INFO", "WARN", "ERROR"};
    private static final String[] MESSAGE_TEMPLATES = {
        "Simple message: {}",
        "Message with two parameters: {} and {}",
        "Complex message with three parameters: {}, {}, and {}",
        "Very long message with many parameters: {}, {}, {}, {}, {}, {}, {}, {}, {}, and {}"
    };

    @Setup(Level.Trial)
    public void setupLogger() {
        // 清理旧的日志文件
        File logFile = new File(LOG_FILE_PATH);
        if (logFile.exists()) {
            logFile.delete();
            System.out.println("JMH Setup: Deleted existing log file: " + LOG_FILE_PATH);
        }

        // 确保 PropertyUtils 缓存是干净的
        PropertyUtils.clearProperties();
        
        // 设置日志文件路径
        System.setProperty(LoggerConstants.HIGH_PERF_LOG_FILE_PROPERTY, LOG_FILE_PATH);
        
        // 强制使用高性能后端工厂
        LogBackendFactory factory = new HighPerformanceFileLogBackendFactory();
        LoggerFactory.setLogBackendFactory(factory);

        this.logger = LoggerFactory.getLogger(DirectBenchmark.class.getName());
        System.out.println("JMH Setup: Logger initialized with backend: " + logger.getLogBackend().getClass().getName());

        // 创建定时任务执行器
        this.executorService = Executors.newScheduledThreadPool(1);
        
        // 初始化随机数生成器
        this.random = new Random();
    }

    @TearDown(Level.Trial)
    public void tearDownLogger() throws Exception {
        System.out.println("JMH TearDown: Attempting to close logger backend.");
        if (this.logger != null && this.logger.getLogBackend() instanceof java.io.Closeable) {
            try {
                ((java.io.Closeable) this.logger.getLogBackend()).close();
                System.out.println("JMH TearDown: Logger backend closed.");
            } catch (Exception e) {
                System.err.println("JMH TearDown: Error closing logger backend: " + e.getMessage());
            }
        }

        // 关闭执行器
        if (this.executorService != null) {
            try {
                System.out.println("JMH TearDown: Shutting down executor service...");
                this.executorService.shutdown();
                if (!this.executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    System.out.println("JMH TearDown: Forcing executor service shutdown...");
                    this.executorService.shutdownNow();
                }
                System.out.println("JMH TearDown: Executor service closed.");
            } catch (InterruptedException e) {
                System.err.println("JMH TearDown: Error shutting down executor service: " + e.getMessage());
                Thread.currentThread().interrupt();
            }
        }

        // 清理日志文件
        File logFile = new File(LOG_FILE_PATH);
        if (logFile.exists()) {
            boolean deleted = logFile.delete();
            System.out.println("JMH TearDown: Log file " + LOG_FILE_PATH + " deleted: " + deleted);
        }
    }

    @Benchmark
    public void logSingleInfoMessage() {
        logger.info("This is a JMH benchmark test log message with a parameter: {}", System.nanoTime());
    }

    @Benchmark
    public void logDifferentLevels() {
        String level = LOG_LEVELS[random.nextInt(LOG_LEVELS.length)];
        switch (level) {
            case "TRACE":
                logger.trace("Trace message: {}", System.nanoTime());
                break;
            case "DEBUG":
                logger.debug("Debug message: {}", System.nanoTime());
                break;
            case "INFO":
                logger.info("Info message: {}", System.nanoTime());
                break;
            case "WARN":
                logger.warn("Warn message: {}", System.nanoTime());
                break;
            case "ERROR":
                logger.error("Error message: {}", System.nanoTime());
                break;
        }
    }

    @Benchmark
    public void logDifferentMessageSizes() {
        String template = MESSAGE_TEMPLATES[random.nextInt(MESSAGE_TEMPLATES.length)];
        Object[] params = new Object[template.split("\\{}").length - 1];
        for (int i = 0; i < params.length; i++) {
            params[i] = System.nanoTime();
        }
        logger.info(template, params);
    }

    @Benchmark
    public void logWithException() {
        try {
            throw new RuntimeException("Test exception");
        } catch (Exception e) {
            logger.error("Error with exception: {}", e.getMessage(), e);
        }
    }

    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
                .include(DirectBenchmark.class.getSimpleName())
                .forks(1)
                .warmupIterations(2)
                .measurementIterations(3)
                .timeout(TimeValue.minutes(10))
                .build();
        new Runner(opt).run();
    }
} 