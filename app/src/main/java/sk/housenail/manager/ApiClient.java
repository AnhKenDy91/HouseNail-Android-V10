package sk.housenail.manager;

import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class ApiClient {
    static final String ORIGIN = "https://housenail.sk";
    static final String API = ORIGIN + "/api/admin";
    static final String AUTH = ORIGIN + "/api/hn-auth";
    private static final String OWNER_COOKIE = "__Host-HNAdmin";

    static class AuthException extends Exception {
        AuthException() { super("LOGIN_REQUIRED"); }
    }

    void verifyOwnerSession() throws Exception {
        JSONObject response = authAction("session");
        if (!response.optBoolean("ok", false) || response.optString("email", "").isEmpty()) {
            throw new AuthException();
        }
    }

    boolean logoutOwnerSession() {
        try {
            JSONObject response = authAction("logout");
            return response.optBoolean("ok", false);
        } catch (Exception ignored) {
            return false;
        }
    }

    JSONObject call(String action, JSONArray args) throws Exception {
        HttpURLConnection conn = open(API);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        applyOwnerCookie(conn);

        JSONObject payload = new JSONObject();
        payload.put("action", action);
        payload.put("args", args == null ? new JSONArray() : args);
        write(conn, payload);

        int code = conn.getResponseCode();
        if (isAuthCode(code)) {
            conn.disconnect();
            throw new AuthException();
        }

        String contentType = conn.getHeaderField("Content-Type");
        if (contentType == null || !contentType.toLowerCase().contains("application/json")) {
            conn.disconnect();
            if (code == 200) throw new AuthException();
            throw new Exception("Không kết nối được máy chủ (HTTP " + code + ").");
        }

        storeRenewedCookie(conn);

        InputStream is = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
        String text = readAll(is);
        conn.disconnect();

        JSONObject envelope = new JSONObject(text);
        if (!envelope.optBoolean("ok", false)) {
            throw new Exception(envelope.optString("error", "Máy chủ từ chối yêu cầu."));
        }
        if (!envelope.has("result")) throw new Exception("Máy chủ không trả result.");

        Object result = envelope.get("result");
        if (result instanceof JSONObject) return (JSONObject) result;

        JSONObject wrapper = new JSONObject();
        wrapper.put("_value", result);
        return wrapper;
    }

    JSONArray callArray(String action, JSONArray args) throws Exception {
        JSONObject result = call(action, args);
        Object value = result.opt("_value");
        if (value instanceof JSONArray) return (JSONArray) value;
        throw new Exception("Dữ liệu API không phải danh sách.");
    }

    private JSONObject authAction(String action) throws Exception {
        HttpURLConnection conn = open(AUTH);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        applyOwnerCookie(conn);

        JSONObject payload = new JSONObject();
        payload.put("action", action);
        write(conn, payload);

        int code = conn.getResponseCode();
        if (isAuthCode(code)) {
            conn.disconnect();
            throw new AuthException();
        }

        String contentType = conn.getHeaderField("Content-Type");
        if (contentType == null || !contentType.toLowerCase().contains("application/json")) {
            conn.disconnect();
            throw new Exception("Máy chủ chưa phản hồi xác thực HouseNail.");
        }

        storeRenewedCookie(conn);
        InputStream is = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
        String text = readAll(is);
        conn.disconnect();
        return new JSONObject(text);
    }

    private HttpURLConnection open(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(25000);
        conn.setReadTimeout(35000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Origin", ORIGIN);
        conn.setUseCaches(false);
        return conn;
    }

    private void applyOwnerCookie(HttpURLConnection conn) throws Exception {
        String all = CookieManager.getInstance().getCookie(ORIGIN);
        if (all == null || all.trim().isEmpty()) throw new AuthException();

        String owner = null;
        for (String part : all.split(";")) {
            String item = part.trim();
            if (item.startsWith(OWNER_COOKIE + "=")) {
                owner = item;
                break;
            }
        }
        if (owner == null || owner.length() <= OWNER_COOKIE.length() + 1) throw new AuthException();
        conn.setRequestProperty("Cookie", owner);
    }

    private void storeRenewedCookie(HttpURLConnection conn) {
        String setCookie = conn.getHeaderField("Set-Cookie");
        if (setCookie != null && setCookie.startsWith(OWNER_COOKIE + "=")) {
            CookieManager.getInstance().setCookie(ORIGIN, setCookie);
            CookieManager.getInstance().flush();
        }
    }

    private static boolean isAuthCode(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 ||
               code == 308 || code == 401 || code == 403;
    }

    private static void write(HttpURLConnection conn, JSONObject payload) throws Exception {
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) out.append(line);
        }
        return out.toString();
    }
}
