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

import java.util.concurrent.TimeUnit;
import java.io.File;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class DirectBenchmark {
    
    private Logger logger;
    private static final String LOG_FILE_PATH = "hplogger-output.log";

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
    }

    @TearDown(Level.Trial)
    public void tearDownLogger() throws Exception {
        System.out.println("JMH TearDown: Attempting to close logger backend.");
        if (this.logger != null && this.logger.getLogBackend() instanceof java.io.Closeable) {
            ((java.io.Closeable) this.logger.getLogBackend()).close();
            System.out.println("JMH TearDown: Logger backend closed.");
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

    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
                .include(DirectBenchmark.class.getSimpleName())
                .forks(1)
                .warmupIterations(2)
                .measurementIterations(3)
                .build();
        new Runner(opt).run();
    }
} 