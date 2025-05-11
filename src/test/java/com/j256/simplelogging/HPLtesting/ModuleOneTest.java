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
} 