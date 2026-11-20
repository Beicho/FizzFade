package io.github.qishui.automix;

/** Resource identity is not populated at the same stage as a pre-rendered source. */
public final class MediaEligibility {
    private MediaEligibility() { }

    public static String resource(String reported, String source) {
        if (reported != null && !reported.isEmpty() && !"FULL".equals(reported)) return "reported_" + reported;
        if (source != null && !source.isEmpty() && !"FULL".equals(source)) return "source_" + source;
        return "FULL".equals(reported) || "FULL".equals(source) ? "full" : "resource_unknown";
    }

    public static boolean same(String expected, String actual) {
        return expected != null && !expected.isEmpty() && expected.equals(actual);
    }
}
