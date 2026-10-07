package com.ynozue.limitgauge;

import android.content.Context;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;

/** Reads the usage JSON from the relay. */
final class Fetcher {
    private static final int MAX_BODY = 256 * 1024;
    private static final long TOTAL_TIMEOUT_NS = 30_000_000_000L;

    static final class HttpException extends IOException {
        final int code;

        HttpException(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    private Fetcher() {}

    /** Blocking GET with a bearer token. Returns the body of a 200 response, throws otherwise. */
    static String get(String endpoint, String token) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setUseCaches(false);
            // Never resend the token to wherever a redirect points.
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "LimitGauge/1 (Android)");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new HttpException(code);
            return readAll(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        long deadline = System.nanoTime() + TOTAL_TIMEOUT_NS;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BODY) throw new IOException("response too large");
                // The read timeout is per read; this stops a relay that drips bytes while we hold the refresh lock.
                if (System.nanoTime() - deadline > 0) throw new SocketTimeoutException("response took too long");
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    /** A short, user-facing explanation of a failed fetch. */
    static String describe(Context c, Throwable e) {
        if (e instanceof HttpException) {
            int code = ((HttpException) e).code;
            if (code == 401 || code == 403) return c.getString(R.string.err_unauthorized);
            if (code == 404) return c.getString(R.string.err_not_found);
            return c.getString(R.string.err_http, code);
        }
        if (e instanceof UnknownHostException) return c.getString(R.string.err_dns);
        if (e instanceof SocketTimeoutException) return c.getString(R.string.err_timeout);
        if (e instanceof JSONException || e instanceof StackOverflowError) return c.getString(R.string.err_bad_json);
        if (e instanceof MalformedURLException) return c.getString(R.string.err_bad_url);
        // Only the type: the message can carry text the server chose (e.g. a fake status line).
        return c.getString(R.string.err_network, e.getClass().getSimpleName());
    }
}
