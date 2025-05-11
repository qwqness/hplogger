package com.j256.simplelogging.backend;

import com.j256.simplelogging.Level;
import com.j256.simplelogging.LogBackend;

public class HighPerformanceFileLogBackend implements LogBackend {
    private final String loggerName;

    public HighPerformanceFileLogBackend(String classLabel) {
        this.loggerName = classLabel;
    }

    @Override
    public boolean isLevelEnabled(Level level) {
        return true;
    }

    @Override
    public void log(Level level, String message) {
        System.out.println("[" + this.loggerName + "] HPFLogBackend msg: " + message);
    }

    @Override
    public void log(Level level, String message, Throwable throwable) {
        System.out.println("[" + this.loggerName + "] HPFLogBackend msg: " + message + 
            ", thr: " + (throwable == null ? "null" : throwable.getClass().getSimpleName()));
    }
} 