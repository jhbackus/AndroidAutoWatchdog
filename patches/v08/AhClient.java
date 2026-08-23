package nl.weekplanner.ah;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class AhClient {
    private static final String BASE = "https://api.ah.nl";
    private static final String AUTH = BASE + "/mobile-auth/v1/auth";
    private static final String PREFS = "ah_auth";
    private static final String KEY_ALIAS = "ah_weekplanner_token_key";
    private static final String CLIENT_ID = "appie-ios";
    private static final String CLIENT_VERSION = "9.28";
    private static final String USER_AGENT = "Appie/9.28 (iPhone17,3; iPhone; CPU OS 26_1 like Mac OS X)";
    private final SharedPreferences prefs;

    public AhClient(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getLoginUrl() {
        return "https://login.ah.nl/login"
                + "?client_id=" + CLIENT_ID
                + "&response_type=code"
                + "&redirect_uri=appie%3A%2F%2Flogin-exit";
    }

    public boolean isConnected() {
        return prefs.getString("refresh_token_enc", null) != null;
    }

    public void disconnect() {
        prefs.edit().clear().apply();
    }

    public void exchangeCode(String code) throws Exception {
        JSONObject body = new JSONObject();
        body.put("clientId", CLIENT_ID);
        body.put("code", code);
        saveTokens(requestJson("POST", AUTH + "/token", null, body));
    }

    public void refresh() throws Exception {
        String refresh = getSecure("refresh_token");
        if (refresh == null) throw new IllegalStateException("Geen AH refresh token");
        JSONObject body = new JSONObject();
        body.put("clientId", CLIENT_ID);
        body.put("refreshToken", refresh);
        saveTokens(requestJson("POST", AUTH + "/token/refresh", null, body));
    }

    private void saveTokens(JSONObject json) throws Exception {
        putSecure("access_token", json.getString("access_token"));
        putSecure("refresh_token", json.getString("refresh_token"));
        long expiresIn = json.optLong("expires_in", 7199);
        prefs.edit().putLong("expires_at", System.currentTimeMillis() + Math.max(60, expiresIn - 60) * 1000L).apply();
    }

    private String validAccessToken() throws Exception {
        if (System.currentTimeMillis() >= prefs.getLong("expires_at", 0)) refresh();
        String token = getSecure("access_token");
        if (token == null) throw new IllegalStateException("Niet gekoppeld met AH");
        return token;
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return kg.generateKey();
    }

    private void putSecure(String name, String value) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        prefs.edit()
                .putString(name + "_enc", Base64.encodeToString(c.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP))
                .putString(name + "_iv", Base64.encodeToString(c.getIV(), Base64.NO_WRAP)).apply();
    }

    private String getSecure(String name) throws Exception {
        String encS = prefs.getString(name + "_enc", null), ivS = prefs.getString(name + "_iv", null);
        if (encS == null || ivS == null) return null;
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, Base64.decode(ivS, Base64.NO_WRAP)));
        return new String(c.doFinal(Base64.decode(encS, Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    public void testConnection() throws Exception {
        requestRaw("GET", BASE + "/mobile-services/shoppinglist/v2/items", validAccessToken(), null);
    }

    public List<AhProduct> searchProducts(String query, int limit) throws Exception {
        String q = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
        JSONObject json = requestJson("GET", BASE + "/mobile-services/product/search/v2?query=" + q + "&sortOn=RELEVANCE", null, null);
        List<AhProduct> out = new ArrayList<>();
        JSONArray products = json.optJSONArray("products");
        if (products == null) {
            JSONObject cards = json.optJSONObject("cards");
            if (cards != null) products = cards.optJSONArray("products");
        }
        if (products == null) return out;
        for (int i = 0; i < products.length() && out.size() < limit; i++) {
            JSONObject p = products.optJSONObject(i);
            if (p == null) continue;
            int id = p.optInt("id", p.optInt("productId", 0));
            String title = p.optString("title", p.optString("name", ""));
            if (id > 0 && !title.isEmpty()) out.add(new AhProduct(id, title));
        }
        return out;
    }

    public void addProductsToShoppingList(List<AhLine> lines) throws Exception {
        String patchError = null;
        try {
            addViaShoppingListV2(lines);
            return;
        } catch (Exception e) {
            patchError = compact(e.getMessage());
        }

        String graphqlError = null;
        try {
            addViaFavoriteListGraphQL(lines);
            return;
        } catch (Exception e) {
            graphqlError = compact(e.getMessage());
        }

        throw new RuntimeException("AH-write diagnose\nPATCH v2: " + patchError + "\nGraphQL: " + graphqlError);
    }

    private void addViaShoppingListV2(List<AhLine> lines) throws Exception {
        JSONArray items = new JSONArray();
        for (AhLine line : lines) {
            JSONObject item = new JSONObject();
            item.put("description", "");
            item.put("productId", line.productId);
            item.put("quantity", Math.max(1, line.quantity));
            item.put("type", "SHOPPABLE");
            item.put("originCode", "PRD");
            item.put("searchTerm", "");
            item.put("strikeThrough", false);
            items.put(item);
        }
        JSONObject body = new JSONObject();
        body.put("items", items);
        requestRaw("PATCH", BASE + "/mobile-services/shoppinglist/v2/items", validAccessToken(), body);
    }

    private void addViaFavoriteListGraphQL(List<AhLine> lines) throws Exception {
        String token = validAccessToken();
        String listId = findFavoriteListId(token);
        if (listId == null || listId.isEmpty()) throw new RuntimeException("geen favorietenlijst-id gevonden");

        JSONArray products = new JSONArray();
        for (AhLine line : lines) {
            JSONObject p = new JSONObject();
            p.put("productId", line.productId);
            p.put("quantity", Math.max(1, line.quantity));
            products.put(p);
        }
        String query = "mutation AddProductsToFavoriteList($favoriteListId: String!, $products: [FavoriteListProductMutation!]!) { favoriteListProductsAddV2(id: $favoriteListId, products: $products) { __typename status errorMessage } }";
        JSONObject vars = new JSONObject();
        vars.put("favoriteListId", listId.toUpperCase());
        vars.put("products", products);
        JSONObject body = new JSONObject();
        body.put("query", query);
        body.put("variables", vars);
        JSONObject resp = new JSONObject(requestRaw("POST", BASE + "/graphql", token, body));
        JSONArray errors = resp.optJSONArray("errors");
        if (errors != null && errors.length() > 0) throw new RuntimeException("GraphQL error: " + errors.optJSONObject(0).optString("message", errors.toString()));
        JSONObject data = resp.optJSONObject("data");
        JSONObject result = data == null ? null : data.optJSONObject("favoriteListProductsAddV2");
        if (result == null) throw new RuntimeException("geen mutation-resultaat");
        String status = result.optString("status", "");
        if (!"SUCCESS".equalsIgnoreCase(status)) throw new RuntimeException(status + " " + result.optString("errorMessage", ""));
    }

    private String findFavoriteListId(String token) throws Exception {
        // First try the current v3 favorite-lists endpoint from the reference client.
        try {
            String raw = requestRaw("GET", BASE + "/mobile-services/lists/v3/lists?productId=1", token, null);
            if (raw.trim().startsWith("[")) {
                JSONArray arr = new JSONArray(raw);
                if (arr.length() > 0) {
                    String id = arr.optJSONObject(0).optString("id", "");
                    if (!id.isEmpty()) return id;
                }
            } else {
                String id = findUuid(new JSONObject(raw));
                if (id != null) return id;
            }
        } catch (Exception ignored) { }

        // Fallback: inspect the working v2 list response for a UUID-like list id.
        try {
            String raw = requestRaw("GET", BASE + "/mobile-services/shoppinglist/v2/items", token, null);
            if (raw.trim().startsWith("{")) return findUuid(new JSONObject(raw));
        } catch (Exception ignored) { }
        return null;
    }

    private String findUuid(Object node) throws Exception {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONArray names = o.names();
            if (names == null) return null;
            for (int i = 0; i < names.length(); i++) {
                String key = names.getString(i);
                Object v = o.opt(key);
                if (v instanceof String) {
                    String s = (String) v;
                    if ((key.toLowerCase().contains("id") || key.toLowerCase().contains("list")) && s.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return s;
                } else if (v instanceof JSONObject || v instanceof JSONArray) {
                    String found = findUuid(v);
                    if (found != null) return found;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                Object v = a.opt(i);
                if (v instanceof JSONObject || v instanceof JSONArray) {
                    String found = findUuid(v);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    private JSONObject requestJson(String method, String endpoint, String bearer, JSONObject body) throws Exception {
        String text = requestRaw(method, endpoint, bearer, body);
        if (text == null || text.trim().isEmpty()) return new JSONObject();
        return new JSONObject(text);
    }

    private String requestRaw(String method, String endpoint, String bearer, JSONObject body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("x-client-name", CLIENT_ID);
        conn.setRequestProperty("x-client-version", CLIENT_VERSION);
        conn.setRequestProperty("x-application", "AHWEBSHOP");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Content-Type", "application/json");
        if (bearer != null) conn.setRequestProperty("Authorization", "Bearer " + bearer);
        if (body != null) {
            conn.setDoOutput(true);
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) { os.write(data); }
        }
        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String text = readAll(stream);
        if (code < 200 || code >= 300) throw new RuntimeException("HTTP " + code + ": " + text);
        return text == null ? "" : text;
    }

    private static String compact(String s) {
        if (s == null) return "onbekende fout";
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > 260 ? s.substring(0, 260) + "…" : s;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        return sb.toString();
    }

    public static class AhProduct {
        public final int id;
        public final String title;
        public AhProduct(int id, String title) { this.id = id; this.title = title; }
    }

    public static class AhLine {
        public final int productId;
        public final int quantity;
        public AhLine(int productId, int quantity) { this.productId = productId; this.quantity = quantity; }
    }
}
