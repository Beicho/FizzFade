package io.github.qishui.automix;

/** Bounded diagnostic text without playback arguments, URLs or account data. */
public final class DiagnosticText {
    public static final int MAX_LOG_CHARS = 48000;
    private DiagnosticText() { }

    public static String append(String history, String entry) {
        String joined = history + entry + "\n";
        if (joined.length() <= MAX_LOG_CHARS) return joined;
        int start = joined.length() - MAX_LOG_CHARS;
        int newline = joined.indexOf('\n', start);
        return newline >= 0 ? joined.substring(newline + 1) : joined.substring(start);
    }

    public static String error(Throwable error) {
        StringBuilder out = new StringBuilder();
        Throwable current = error;
        for (int cause = 0; current != null && cause < 5; cause++) {
            if (cause != 0) out.append("Caused by: ");
            out.append(current.getClass().getName());
            // Reflection/bootstrap messages identify missing classes or methods. Other exception
            // messages may contain playback URLs, so only their types and frames are recorded.
            if (current instanceof ReflectiveOperationException || current instanceof LinkageError) {
                String message = current.getMessage();
                if (message != null) out.append(": ").append(safe(message, 500));
            }
            out.append('\n');
            StackTraceElement[] frames = current.getStackTrace();
            for (int i = 0; i < Math.min(16, frames.length); i++) {
                out.append("  at ").append(frames[i]).append('\n');
            }
            Throwable next = current.getCause();
            if (next == current) break;
            current = next;
        }
        return out.toString();
    }

    public static String safe(String text, int limit) {
        if (text == null) return "";
        String clean = text.replaceAll("(?i)(https?|file|content)://[^\\s]+", "[redacted-uri]")
            .replaceAll("[\\r\\n\\t]", " ");
        return clean.length() <= limit ? clean : clean.substring(0, limit);
    }
}
