package com.j256.simplelogging.HPLtesting;

import com.j256.simplelogging.Logger;
import com.j256.simplelogging.LoggerFactory;
import com.j256.simplelogging.LogBackend;
import com.j256.simplelogging.BaseLogger;
import com.j256.simplelogging.backend.HighPerformanceFileLogBackend;
import org.junit.Test;
import static org.junit.Assert.*;
import java.lang.reflect.Method;

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
} 