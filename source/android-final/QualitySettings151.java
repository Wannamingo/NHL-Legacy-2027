package org.nhllegacy.port;

final class QualitySettings151 {
    static final String KEY = "match_resolution151";
    static final String[] VALUES = {"540p", "720p"};
    private QualitySettings151() {}
    static String normalize(String selected) {
        return "720p".equals(selected) ? "720p" : "540p";
    }
    static void apply(String selected) throws android.system.ErrnoException {
        // The match hook selects its own complete chain. Keep the guest display,
        // presenter and interface at the existing 720p mode in both cases.
        android.system.Os.setenv("NHL_INTERNAL_RESOLUTION", "720p", true);
        android.system.Os.setenv("NHL_MATCH_RESOLUTION", normalize(selected), true);
    }
}
