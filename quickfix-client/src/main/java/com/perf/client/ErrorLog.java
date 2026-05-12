package com.perf.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Routes transient and channel-break errors to the dedicated {@code errors.log} appender
 * configured in {@code logback-client.xml}. Thread-safe via Logback's internal locking.
 */
public final class ErrorLog implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ErrorLog.class);

    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Writes a session-start marker so multiple runs are easy to distinguish in the log file.
     * No longer throws IOException — the logback appender manages the file handle.
     */
    public ErrorLog(String path) {
        LOGGER.info("=== Session started {} ===", FMT.format(LocalDateTime.now()));
    }

    /**
     * Logs a transient error (channel still alive). Stack trace is intentionally omitted
     * to keep the file readable under high error rates.
     */
    public void logTransient(String context, long count, Throwable cause) {
        LOGGER.info("%s  transient #%-6d  ctx=%-25s  type=%-35s  msg=%s".formatted(
            FMT.format(LocalDateTime.now()),
            count,
            context,
            cause.getClass().getSimpleName(),
            cause.getMessage() != null ? cause.getMessage() : "(null)"));
    }

    /**
     * Logs a channel-break error. Logback prints the full stack trace via the
     * {@code %ex{full}} token in the ERRORS_FILE appender pattern.
     */
    public void logChannelBroken(String context, long count, Throwable cause) {
        LOGGER.error("%n%s  channel-broken #%d  ctx=%s  type=%s".formatted(
            FMT.format(LocalDateTime.now()), count, context,
            cause.getClass().getSimpleName()), cause);
    }

    /**
     * Logs a channel-break event with no associated exception (e.g. onDisconnect).
     */
    public void logChannelBroken(String context, long count, String reason) {
        LOGGER.error("%n%s  channel-broken #%d  ctx=%s  reason=%s".formatted(
            FMT.format(LocalDateTime.now()), count, context, reason));
    }

    /**
     * Logs a QFJ session-level event captured from {@code Log.onEvent} / {@code onErrorEvent} /
     * {@code onWarnEvent}. The {@code level} string ("EVENT", "WARN", "ERROR") determines the
     * SLF4J log level so that WARN/ERROR events also propagate to the console appender.
     */
    public void logSessionEvent(String sessionID, String level, String text) {
        String msg = "%s  session-%-5s  sid=%-40s  %s".formatted(
            FMT.format(LocalDateTime.now()), level, sessionID, text);
        // All QFJ session events go to errors.log only; the per-second stat line on the
        // console already shows transient_errors and channel_breaks counts.
        LOGGER.info(msg);
    }

    @Override
    public void close() {
        LOGGER.info("=== Session ended {} ===", FMT.format(LocalDateTime.now()));
        // Logback manages the file handle; no explicit close needed.
    }
}
