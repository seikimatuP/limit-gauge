package com.ynozue.limitgauge;

/**
 * Plain-JVM checks for the Android-free logic (UsageData, SetupLink).
 * Run with any org.json implementation on the classpath; see tools/run-logic-tests.sh.
 */
public final class LogicTest {
    private static int passed, failed;

    public static void main(String[] args) throws Exception {
        long now = 1_760_000_000L;

        // --- UsageData: relay response shape ---
        UsageData d = UsageData.parse("{\"v\":1,\"updated_at\":" + (now - 60) + ","
                + "\"five_hour\":{\"used_percentage\":23.5,\"resets_at\":" + (now + 3600) + ",\"captured_at\":" + (now - 120) + "},"
                + "\"seven_day\":{\"used_percentage\":46,\"resets_at\":" + (now + 5 * 86400) + ",\"captured_at\":" + (now - 60) + "}}");
        eq("five left", 76, d.fiveHour.leftPercent(now));
        eq("five used", 24, d.fiveHour.usedPercentRounded(now));
        eq("week left (exact int)", 54, d.sevenDay.leftPercent(now));
        eq("week used (exact int)", 46, d.sevenDay.usedPercentRounded(now));
        eq("updated_at", now - 60, d.updatedAt);
        eq("not empty", false, d.isEmpty());
        eq("expired window is full", 100, d.fiveHour.leftPercent(now + 3600));
        eq("expired flag", true, d.fiveHour.isExpired(now + 3600));
        eq("seconds until reset", 3600L, d.fiveHour.secondsUntilReset(now));

        // --- rounding edges ---
        eq("0.01% used -> 99 left", 99, win(0.01, now).leftPercent(now));
        eq("0% used -> 100 left", 100, win(0, now).leftPercent(now));
        eq("99.99% used -> 0 left", 0, win(99.99, now).leftPercent(now));
        eq("100% used -> 0 left", 0, win(100, now).leftPercent(now));
        eq("23.1% used -> 76 left", 76, win(23.1, now).leftPercent(now));
        eq("clamp >100", 0, UsageData.parse("{\"five_hour\":{\"used_percentage\":250,\"resets_at\":" + (now + 9) + "}}").fiveHour.leftPercent(now));
        eq("clamp <0", 100, UsageData.parse("{\"five_hour\":{\"used_percentage\":-5,\"resets_at\":" + (now + 9) + "}}").fiveHour.leftPercent(now));

        // --- Claude Code status line shape (rate_limits nested), ms timestamps, ISO strings ---
        UsageData s = UsageData.parse("{\"model\":{\"display_name\":\"Opus\"},\"rate_limits\":{"
                + "\"five_hour\":{\"used_percentage\":10,\"resets_at\":" + ((now + 100) * 1000L) + "},"
                + "\"seven_day\":{\"used_percentage\":\"41.2\",\"resets_at\":\"2025-10-14T17:53:20+09:00\"}}}");
        eq("ms resets_at", now + 100, s.fiveHour.resetsAt);
        eq("numeric string used", 58, s.sevenDay.leftPercent(now));
        eq("ISO resets_at with offset", 1_760_432_000L, s.sevenDay.resetsAt);
        eq("no captured -> updated 0", 0L, s.updatedAt);

        // --- missing / broken windows ---
        UsageData e = UsageData.parse("{\"v\":1,\"five_hour\":null,\"seven_day\":{\"used_percentage\":null,\"resets_at\":1}}");
        eq("null window", true, e.fiveHour == null);
        eq("null used", true, e.sevenDay == null);
        eq("empty", true, e.isEmpty());
        UsageData noReset = UsageData.parse("{\"five_hour\":{\"used_percentage\":5}}");
        eq("no resets_at -> null", true, noReset.fiveHour == null);
        UsageData fallback = UsageData.parse("{\"five_hour\":{\"used_percentage\":5,\"resets_at\":" + now + ",\"captured_at\":" + (now - 5) + "},"
                + "\"seven_day\":{\"used_percentage\":6,\"resets_at\":" + now + ",\"captured_at\":" + (now - 9) + "}}");
        eq("updated falls back to max captured", now - 5, fallback.updatedAt);
        boolean threw = false;
        try { UsageData.parse("not json"); } catch (Exception ex) { threw = true; }
        eq("bad json throws", true, threw);

        // --- SetupLink ---
        SetupLink a = SetupLink.parse("limitgauge://config?u=https%3A%2F%2Flimit-gauge-relay.foo.workers.dev&t=AbC_-123");
        eq("deep link url", "https://limit-gauge-relay.foo.workers.dev", a == null ? null : a.url);
        eq("deep link token", "AbC_-123", a == null ? null : a.token);
        SetupLink b = SetupLink.parse("  https://limit-gauge-relay.foo.workers.dev/pair#t=tok123  ");
        eq("pair url -> origin", "https://limit-gauge-relay.foo.workers.dev", b == null ? null : b.url);
        eq("pair token", "tok123", b == null ? null : b.token);
        SetupLink c = SetupLink.parse("https://x.workers.dev/pair#t=tok&u=http%3A%2F%2F100.64.0.7%3A8787");
        eq("pair explicit u", "http://100.64.0.7:8787", c == null ? null : c.url);
        SetupLink plus = SetupLink.parse("limitgauge://config?u=https%3A%2F%2Fa.dev&t=a%2Bb%2Fc%3D");
        eq("encoded + / = survive", "a+b/c=", plus == null ? null : plus.token);
        eq("missing token", null, SetupLink.parse("limitgauge://config?u=https%3A%2F%2Fa.dev"));
        eq("ftp url", null, SetupLink.parse("limitgauge://config?u=ftp%3A%2F%2Fa.dev&t=x"));
        eq("token with space", null, SetupLink.parse("limitgauge://config?u=https%3A%2F%2Fa.dev&t=a%20b"));
        eq("other scheme", null, SetupLink.parse("javascript:alert(1)"));
        eq("plain text", null, SetupLink.parse("hello world"));
        eq("pair without token", null, SetupLink.parse("https://x.workers.dev/pair"));
        eq("empty", null, SetupLink.parse(""));
        eq("null", null, SetupLink.parse(null));

        eq("endpoint origin", "https://x.workers.dev/v1/usage", SetupLink.endpointFor("https://x.workers.dev"));
        eq("endpoint slash", "https://x.workers.dev/v1/usage", SetupLink.endpointFor("https://x.workers.dev/"));
        eq("endpoint port", "http://100.64.0.7:8787/v1/usage", SetupLink.endpointFor(" http://100.64.0.7:8787 "));
        eq("endpoint custom path kept", "https://x.dev/usage.json", SetupLink.endpointFor("https://x.dev/usage.json"));
        eq("endpoint feed path kept", "https://x.dev/v1/usage/work", SetupLink.endpointFor("https://x.dev/v1/usage/work"));
        eq("valid url", true, SetupLink.isValidUrl("https://a.dev"));
        eq("invalid url no host", false, SetupLink.isValidUrl("https://"));
        eq("invalid url scheme", false, SetupLink.isValidUrl("a.dev"));

        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static UsageData.Window win(double used, long now) {
        return new UsageData.Window(used, now + 1000, now);
    }

    private static void eq(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
