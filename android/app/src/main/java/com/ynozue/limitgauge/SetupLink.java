package com.ynozue.limitgauge;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Relay connection settings carried by a setup link. Two forms are accepted:
 *
 *   limitgauge://config?u=<relay url>&t=<token>          (deep link opened from the pairing page)
 *   https://<relay host>/pair#t=<token>[&u=<relay url>]   (the pairing page URL itself, e.g. pasted)
 *
 * Pure Java so it can be unit-tested on a plain JVM.
 */
public final class SetupLink {

    public final String url;
    public final String token;

    SetupLink(String url, String token) {
        this.url = url;
        this.token = token;
    }

    /** Returns null when the text is not a usable setup link. */
    public static SetupLink parse(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (s.isEmpty()) return null;
        URI uri;
        try {
            uri = new URI(s);
        } catch (URISyntaxException e) {
            return null;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        Map<String, String> params;
        String url;
        if (scheme.equals("limitgauge")) {
            params = parseParams(uri.getRawQuery());
            url = params.get("u");
        } else if (scheme.equals("https") || scheme.equals("http")) {
            params = parseParams(uri.getRawFragment());
            url = params.get("u");
            if (url == null || url.isEmpty()) {
                if (uri.getHost() == null) return null;
                url = scheme + "://" + uri.getRawAuthority();
            }
        } else {
            return null;
        }
        String token = params.get("t");
        if (!isValidUrl(url) || !isValidToken(token)) return null;
        return new SetupLink(url.trim(), token.trim());
    }

    static boolean isValidUrl(String url) {
        if (url == null) return false;
        String u = url.trim();
        boolean https = u.regionMatches(true, 0, "https://", 0, 8);
        if (!https && !u.regionMatches(true, 0, "http://", 0, 7)) return false;
        try {
            URI parsed = new URI(u);
            String host = parsed.getHost();
            // "user@host" makes the visible start of the URL differ from where it connects.
            if (host == null || host.isEmpty() || parsed.getRawUserInfo() != null) return false;
            // Plain http would send the token in the clear, so it is only for relays off the internet.
            return https || isLocalHost(host);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** True for LAN and Tailscale addresses: private IPv4/IPv6 ranges, localhost, *.local, *.ts.net. */
    static boolean isLocalHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".ts.net")) {
            return true;
        }
        if (h.indexOf('.') < 0 && h.indexOf(':') < 0) return true; // single-label LAN / MagicDNS name
        String[] p = h.split("\\.", -1);
        if (p.length == 4) {
            int[] o = new int[4];
            for (int i = 0; i < 4; i++) {
                if (!p[i].matches("\\d{1,3}")) return false;
                o[i] = Integer.parseInt(p[i]);
            }
            int a = o[0], b = o[1];
            return a == 10 || a == 127 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168)
                    || (a == 169 && b == 254) || (a == 100 && b >= 64 && b <= 127);
        }
        return h.equals("::1") || h.matches("f[cd][0-9a-f]{2}:.*") || h.matches("fe[89ab][0-9a-f]:.*");
    }

    /** What the confirmation dialog shows: scheme, host, port and path, so an http downgrade is visible. */
    static String displayOrigin(String url) {
        try {
            URI u = new URI(url.trim());
            String path = u.getRawPath() == null || u.getRawPath().equals("/") ? "" : u.getRawPath();
            return u.getScheme() + "://" + u.getHost() + (u.getPort() == -1 ? "" : ":" + u.getPort()) + path;
        } catch (URISyntaxException | RuntimeException e) {
            return url;
        }
    }

    static boolean isValidToken(String token) {
        if (token == null) return false;
        String t = token.trim();
        if (t.isEmpty() || t.length() > 512) return false;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch <= ' ' || ch >= 127) return false;
        }
        return true;
    }

    /**
     * Turns the saved relay URL into the usage endpoint: a bare origin such as
     * https://limit-gauge-relay.example.workers.dev gets "/v1/usage" appended;
     * a URL that already has a path is used as-is (handy for self-hosted JSON).
     */
    public static String endpointFor(String base) {
        String b = base == null ? "" : base.trim();
        String path;
        try {
            path = new URI(b).getRawPath();
        } catch (URISyntaxException e) {
            return b;
        }
        if (path == null || path.isEmpty() || path.equals("/")) {
            while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
            return b + "/v1/usage";
        }
        return b;
    }

    private static Map<String, String> parseParams(String raw) {
        Map<String, String> out = new HashMap<String, String>();
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            String v = eq < 0 ? "" : part.substring(eq + 1);
            out.put(decode(k), decode(v));
        }
        return out;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return s;
        }
    }
}
