package com.j256.simplelogging.event;

import com.j256.simplelogging.Level;

public class LogEvent {
    private final long timestamp;
    private final Level level;
    private final String message;
    private final Object[] arguments;
    private final Throwable throwable;
    private final String threadName;
    private final String loggerName;
    private final String logCode;

    public LogEvent(long timestamp, Level level, String message, Object[] arguments, 
                   Throwable throwable, String threadName, String loggerName, String logCode) {
        this.timestamp = timestamp;
        this.level = level;
        this.message = message;
        this.arguments = arguments;
        this.throwable = throwable;
        this.threadName = threadName;
        this.loggerName = loggerName;
        this.logCode = logCode;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public Level getLevel() {
        return level;
    }

    public String getMessage() {
        return message;
    }

    public Object[] getArguments() {
        return arguments;
    }

    public Throwable getThrowable() {
        return throwable;
    }

    public String getThreadName() {
        return threadName;
    }

    public String getLoggerName() {
        return loggerName;
    }

    public String getLogCode() {
        return logCode;
    }
} 