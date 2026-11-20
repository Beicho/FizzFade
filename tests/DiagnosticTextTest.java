package io.github.qishui.automix;

public final class DiagnosticTextTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        String history = "";
        for (int i = 0; i < 3000; i++) history = DiagnosticText.append(history, "event-" + i + " detail-detail-detail");
        check(history.length() <= DiagnosticText.MAX_LOG_CHARS, "bounded log");
        check(!history.contains("event-0 "), "old entries evicted");
        check(history.contains("event-2999 "), "newest entry kept");
        IllegalStateException playback = new IllegalStateException("https://secret.example/song?token=private");
        String output = DiagnosticText.error(playback);
        check(output.contains("IllegalStateException") && output.contains("DiagnosticTextTest.main"), "type and frames present");
        check(!output.contains("secret.example") && !output.contains("token"), "playback message omitted");
        NoSuchMethodException missing = new NoSuchMethodException("Host.missing(java.lang.String)");
        missing.initCause(playback);
        output = DiagnosticText.error(missing);
        check(output.contains("Host.missing(java.lang.String)"), "reflection detail kept");
        check(output.contains("Caused by: java.lang.IllegalStateException"), "cause retained");
        output = DiagnosticText.error(new NoClassDefFoundError("https://secret.example/class?key=private"));
        check(output.contains("[redacted-uri]") && !output.contains("private"), "reflection URI redacted");
        check(DiagnosticText.safe(null, 100).isEmpty(), "null metadata safe");
        System.out.println("PASS diagnostic retention, exception frames/causes and playback-data redaction");
    }
}
