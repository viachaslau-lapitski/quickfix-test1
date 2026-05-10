package com.perf.client;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Writes transient and channel-break errors to a dedicated file so they don't
 * pollute the main console output. Thread-safe via synchronized writes.
 */
public final class ErrorLog implements AutoCloseable {

    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PrintWriter writer;

    /**
     * Opens (or appends to) the given path in the current working directory.
     * Writes a session-start marker so multiple runs are easy to distinguish.
     */
    public ErrorLog(String path) throws IOException {
        this.writer = new PrintWriter(new FileWriter(path, /* append */ true), true);
        writer.printf("%n=== Session started %s ===%n", FMT.format(LocalDateTime.now()));
    }

    /**
     * Logs a transient error (channel still alive). Only exception type and message
     * are recorded — no stack trace — to keep the file readable under high error rates.
     */
    public synchronized void logTransient(String context, long count, Throwable cause) {
        writer.printf("%s  transient #%-6d  ctx=%-25s  type=%-35s  msg=%s%n",
            FMT.format(LocalDateTime.now()),
            count,
            context,
            cause.getClass().getSimpleName(),
            cause.getMessage() != null ? cause.getMessage() : "(null)");
    }

    /**
     * Logs a channel-break error with a full stack trace.
     */
    public synchronized void logChannelBroken(String context, long count, Throwable cause) {
        writer.printf("%n%s  channel-broken #%d  ctx=%s  type=%s%n",
            FMT.format(LocalDateTime.now()), count, context,
            cause.getClass().getSimpleName());
        cause.printStackTrace(writer);
        writer.println();
    }

    /**
     * Logs a channel-break event with no associated exception (e.g. onDisconnect).
     */
    public synchronized void logChannelBroken(String context, long count, String reason) {
        writer.printf("%n%s  channel-broken #%d  ctx=%s  reason=%s%n",
            FMT.format(LocalDateTime.now()), count, context, reason);
    }

    /**
     * Logs a QFJ session-level error or warn event (e.g. disconnect reason, SSL error).
     * Captured from {@code Log.onErrorEvent}/{@code onWarnEvent} — reveals the root cause
     * of disconnects that would otherwise only appear as a bare {@code onDisconnect()} callback.
     */
    public synchronized void logSessionEvent(String sessionID, String level, String text) {
        writer.printf("%s  session-%-5s  sid=%-40s  %s%n",
            FMT.format(LocalDateTime.now()), level, sessionID, text);
    }

    @Override
    public void close() {
        writer.close();
    }
}
