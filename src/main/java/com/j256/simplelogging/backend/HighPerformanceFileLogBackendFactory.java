package com.j256.simplelogging.backend;

import com.j256.simplelogging.LogBackend;
import com.j256.simplelogging.LogBackendFactory;

public class HighPerformanceFileLogBackendFactory implements LogBackendFactory {
    @Override
    public LogBackend createLogBackend(String classLabel) {
        return new HighPerformanceFileLogBackend(classLabel);
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
} 