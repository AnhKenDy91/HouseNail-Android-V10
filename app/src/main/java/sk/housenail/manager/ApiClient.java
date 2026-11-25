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

    String verifyOwnerSession() throws Exception {
        JSONObject response = authAction("session");
        if (!response.optBoolean("ok", false) || response.optString("email", "").isEmpty()) {
            throw new AuthException();
        }
        String role = response.optString("role", "owner");
        if (!("owner".equals(role) || "staff".equals(role))) {
            throw new Exception("Vai trò HouseNail không hợp lệ.");
        }
        return role;
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
            throw new Exception("API " + action + ": máy chủ trả dữ liệu không phải JSON (HTTP " + code + ").");
        }

        storeRenewedCookie(conn);

        InputStream is = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
        String text = readAll(is);
        conn.disconnect();

        JSONObject envelope = new JSONObject(text);
        if (!envelope.optBoolean("ok", false)) {
            String reason = envelope.optString("error", "Máy chủ từ chối yêu cầu.");
            String codeText = envelope.optString("code", "");
            throw new Exception("API " + action + " (HTTP " + code + "): " + reason + (codeText.isEmpty() ? "" : " [" + codeText + "]"));
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

    JSONObject staffAuthAction(String action, JSONObject fields) throws Exception {
        if (!java.util.Arrays.asList("staffList", "staffAudit", "staffCreate", "staffSetDisabled", "staffResetPassword").contains(action))
            throw new IllegalArgumentException("Unsupported staff action");
        HttpURLConnection conn = open(AUTH);
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            applyOwnerCookie(conn);
            JSONObject payload = fields == null ? new JSONObject() : new JSONObject(fields.toString());
            payload.put("action", action);
            write(conn, payload);
            int code = conn.getResponseCode();
            if (code == 403) throw new Exception("Chỉ chủ tiệm được quản lý nhân viên.");
            if (isAuthCode(code)) throw new AuthException();
            String type = conn.getHeaderField("Content-Type");
            if (type == null || !type.toLowerCase(java.util.Locale.ROOT).contains("application/json"))
                throw new Exception("Máy chủ trả dữ liệu không hợp lệ.");
            JSONObject reply = new JSONObject(readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream()));
            if (code < 200 || code >= 300 || !reply.optBoolean("ok", false))
                throw new Exception(reply.optString("error", "Máy chủ từ chối yêu cầu."));
            storeRenewedCookie(conn);
            return reply;
        } finally { conn.disconnect(); }
    }

    static String makePasswordProof(String email, String password) throws Exception {
        String normalized = email.trim().toLowerCase(java.util.Locale.ROOT);
        if (password.isEmpty()) throw new IllegalArgumentException("Mật khẩu trống.");
        javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(password.toCharArray(),
            ("HouseNail-V265-client|" + normalized).getBytes(StandardCharsets.UTF_8), 310000, 256);
        byte[] bytes = null;
        try {
            bytes = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
        } finally {
            spec.clearPassword();
            if (bytes != null) java.util.Arrays.fill(bytes, (byte) 0);
        }
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
