package top.mcocet.aIBuilding.util;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.text.MessageFormat;

/**
 * 捕获服务器控制台日志的环形缓冲区，
 * 供 AI 的 get_console_logs 工具读取。
 */
public class LogBuffer extends Handler {

    private final Deque<String> buffer;
    private final int capacity;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

    public LogBuffer(int capacity) {
        this.capacity = Math.max(50, capacity);
        this.buffer = new ArrayDeque<>(this.capacity);
        setLevel(Level.ALL);
    }

    @Override
    public synchronized void publish(LogRecord record) {
        if (!isLoggable(record)) {
            return;
        }
        String line = timeFormat.format(new Date(record.getMillis()))
                + " [" + record.getLevel().getName() + "] " + format(record);
        buffer.addLast(line);
        while (buffer.size() > capacity) {
            buffer.pollFirst();
        }
    }

    /**
     * 获取最近的 count 条日志。
     */
    public synchronized List<String> getLastLines(int count) {
        int n = Math.min(Math.max(count, 1), buffer.size());
        List<String> all = new ArrayList<>(buffer);
        return new ArrayList<>(all.subList(all.size() - n, all.size()));
    }

    public synchronized int size() {
        return buffer.size();
    }

    private static String format(LogRecord record) {
        String message = record.getMessage();
        Object[] params = record.getParameters();
        if (params != null && params.length > 0 && message != null && message.contains("{0}")) {
            try {
                return MessageFormat.format(message, params);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return String.valueOf(message);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
}
