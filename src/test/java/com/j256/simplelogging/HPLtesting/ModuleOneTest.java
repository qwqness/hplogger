package com.j256.simplelogging.HPLtesting;

import com.j256.simplelogging.Logger;
import com.j256.simplelogging.LoggerFactory;
import com.j256.simplelogging.LogBackend;
import com.j256.simplelogging.BaseLogger;
import com.j256.simplelogging.backend.HighPerformanceFileLogBackend;
import org.junit.Test;
import static org.junit.Assert.*;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.io.File;
import com.j256.simplelogging.LoggerConstants;
import com.j256.simplelogging.PropertyUtils;

public class ModuleOneTest {

    @Test
    public void testHighPerformanceBackendIsLoaded() throws Exception {
        // 清除任何先前设置的工厂
        LoggerFactory.setLogBackendFactory(null);
        
        // 获取 Logger 实例
        Logger logger = LoggerFactory.getLogger(ModuleOneTest.class);
        assertNotNull("Logger should not be null", logger);
        
        // 使用反射获取 LogBackend 实例
        Method getLogBackendMethod = BaseLogger.class.getDeclaredMethod("getLogBackend");
        getLogBackendMethod.setAccessible(true);
        LogBackend backend = (LogBackend) getLogBackendMethod.invoke(logger);
        assertNotNull("LogBackend should not be null", backend);
        
        // 验证是否是 HighPerformanceFileLogBackend 实例
        assertTrue("LogBackend should be an instance of HighPerformanceFileLogBackend", 
            backend instanceof HighPerformanceFileLogBackend);
    }

    @Test
    public void testHighPerformanceBackendLogsToConsole() {
        // 获取 Logger 实例
        Logger logger = LoggerFactory.getLogger("ConsoleOutputTest");
        
        // 测试普通日志消息
        logger.info("This is a test message");
        
        // 测试带异常的日志消息
        logger.error("This is an error test message", new RuntimeException("TestException"));

        try {
            System.out.println("ModuleOneTest: Main test thread [" + Thread.currentThread().getName() + "] sleeping for a short while to allow consumer to process...");
            Thread.sleep(500); // 休眠 500 毫秒
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println("ModuleOneTest: Main test thread [" + Thread.currentThread().getName() + "] woke up.");
    }

    @Test
    public void testBatchAndTimedFlush() throws Exception {
        LoggerFactory.setLogBackendFactory(null); // Ensure our factory is picked up
        Logger logger = LoggerFactory.getLogger("BatchFlushTest");
        int batchSizeTriggerCount = 250; // configuredBatchSize is 200
        long flushInterval = 5000; // configuredFlushIntervalMillis is 5000ms
        long testSleepTime = flushInterval + 2000; // Sleep a bit longer than flush interval

        System.out.println("BatchFlushTest: Generating " + batchSizeTriggerCount + " messages to trigger batch flush...");
        for (int i = 0; i < batchSizeTriggerCount; i++) {
            logger.info("Batch message # " + (i + 1));
            if (i == 199) { // Just before configuredBatchSize might trigger
                Thread.sleep(10); // Small pause to ensure distinct timestamps if needed
            }
        }
        System.out.println("BatchFlushTest: Finished generating messages. Sleeping for " + testSleepTime + "ms to observe timed flush if any pending...");

        // At this point, the first batch of ~200 should have been written due to batchSize.
        // Any remaining (e.g., 50) should be flushed by interval.

        Thread.sleep(testSleepTime);

        // Log a final message after the sleep, this might form a new small batch flushed on shutdown
        logger.warn("BatchFlushTest: Final message after sleep.");
        System.out.println("BatchFlushTest: Woke up from sleep. Test ending.");
        Thread.sleep(500); // Allow shutdown hook and final flushes
    }

    @Test
    public void testConfigurationLoadingViaSystemProperties() throws Exception {
        // 定义测试用的配置值
        String testLogFilePath = "target/test-logs/system-property-config.log";
        int testBatchSize = 300;
        long testFlushInterval = 6000L;

        // 保存原始系统属性值
        String originalLogFilePath = System.getProperty(LoggerConstants.HIGH_PERF_LOG_FILE_PROPERTY);
        String originalBatchSize = System.getProperty(LoggerConstants.HIGH_PERF_BATCH_SIZE_PROPERTY);
        String originalFlushInterval = System.getProperty(LoggerConstants.HIGH_PERF_FLUSH_INTERVAL_PROPERTY);

        try {
            // 设置测试配置值作为系统属性
            System.setProperty(LoggerConstants.HIGH_PERF_LOG_FILE_PROPERTY, testLogFilePath);
            System.setProperty(LoggerConstants.HIGH_PERF_BATCH_SIZE_PROPERTY, String.valueOf(testBatchSize));
            System.setProperty(LoggerConstants.HIGH_PERF_FLUSH_INTERVAL_PROPERTY, String.valueOf(testFlushInterval));

            // 清除 LoggerFactory 缓存并获取一个新的 Logger 实例
            LoggerFactory.setLogBackendFactory(null);
            Logger logger = LoggerFactory.getLogger("ConfigTestSysProp");

            // 使用反射递归查找父类的方法获取 LogBackend 实例
            Method getLogBackendMethod = null;
            Class<?> clazz = logger.getClass(); // 从 Logger 类开始查找
            while (clazz != null) {
                try {
                    getLogBackendMethod = clazz.getDeclaredMethod("getLogBackend");
                    break; // 方法找到，跳出循环
                } catch (NoSuchMethodException e) {
                    clazz = clazz.getSuperclass(); // 在父类中继续查找
                }
            }
            assertNotNull("getLogBackend method should be found in Logger or its superclasses", getLogBackendMethod);
            getLogBackendMethod.setAccessible(true);
            LogBackend logBackend = (LogBackend) getLogBackendMethod.invoke(logger);
            assertTrue(logBackend instanceof HighPerformanceFileLogBackend);
            HighPerformanceFileLogBackend hpBackend = (HighPerformanceFileLogBackend) logBackend;

            // 使用反射访问并断言字段值
            Field logFilePathField = HighPerformanceFileLogBackend.class.getDeclaredField("logFilePath");
            logFilePathField.setAccessible(true);
            assertEquals(testLogFilePath, logFilePathField.get(hpBackend));

            Field configuredBatchSizeField = HighPerformanceFileLogBackend.class.getDeclaredField("configuredBatchSize");
            configuredBatchSizeField.setAccessible(true);
            assertEquals(testBatchSize, configuredBatchSizeField.getInt(hpBackend)); // 使用 getInt 获取 int 字段

            Field configuredFlushIntervalMillisField = HighPerformanceFileLogBackend.class.getDeclaredField("configuredFlushIntervalMillis");
            configuredFlushIntervalMillisField.setAccessible(true);
            assertEquals(testFlushInterval, configuredFlushIntervalMillisField.getLong(hpBackend)); // 使用 getLong 获取 long 字段

            // 记录一条测试日志
            logger.info("Test log message for system property configuration.");
            Thread.sleep(200); // 短暂休眠以确保日志有机会被处理和写入
        } finally {
            // 恢复原始系统属性值
            if (originalLogFilePath != null) {
                System.setProperty(LoggerConstants.HIGH_PERF_LOG_FILE_PROPERTY, originalLogFilePath);
            } else {
                System.clearProperty(LoggerConstants.HIGH_PERF_LOG_FILE_PROPERTY);
            }
            if (originalBatchSize != null) {
                System.setProperty(LoggerConstants.HIGH_PERF_BATCH_SIZE_PROPERTY, originalBatchSize);
            } else {
                System.clearProperty(LoggerConstants.HIGH_PERF_BATCH_SIZE_PROPERTY);
            }
            if (originalFlushInterval != null) {
                System.setProperty(LoggerConstants.HIGH_PERF_FLUSH_INTERVAL_PROPERTY, originalFlushInterval);
            } else {
                System.clearProperty(LoggerConstants.HIGH_PERF_FLUSH_INTERVAL_PROPERTY);
            }

            // 清除缓存
            PropertyUtils.clearProperties();
            LoggerFactory.setLogBackendFactory(null);

            // 删除测试生成的日志文件
            new File(testLogFilePath).delete();
        }
    }
} 