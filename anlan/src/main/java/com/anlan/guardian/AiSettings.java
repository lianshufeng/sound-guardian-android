package com.anlan.guardian;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** 应用私有配置；发布包不内置地址、模型或密钥。密钥只以 Keystore 密文落盘。 */
final class AiSettings {
    private static final String ALIAS = "anlan.ai.token.v1";
    final SharedPreferences preferences;
    static final class Profile {
        final boolean enabled, disableThinking, cue, webSearch;
        final String baseUrl, token, model;
        Profile(boolean enabled, String url, String token, String model, boolean thinking, boolean cue) {
            this(enabled, url, token, model, thinking, cue, true);
        }
        Profile(boolean enabled, String url, String token, String model, boolean thinking, boolean cue, boolean webSearch) {
            this.enabled = enabled; baseUrl = url; this.token = token; this.model = model;
            disableThinking = thinking; this.cue = cue; this.webSearch = webSearch;
        }
        boolean ready() { return enabled && !baseUrl.isEmpty() && !model.isEmpty(); }
    }
    AiSettings(Context context) { preferences = context.getSharedPreferences("ai_model", Context.MODE_PRIVATE); }
    static String normalizeUrl(String value) {
        String url = value.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || url.endsWith("/chat/completions"))
                throw new IllegalArgumentException();
        } catch (Exception error) { throw new IllegalArgumentException("请填写 HTTPS API 根地址（例如 https://example.com/v1）"); }
        return url;
    }
    Profile load() {
        String token = "";
        String encrypted = preferences.getString("token", "");
        if (!encrypted.isEmpty()) {
            try {
                String[] parts = encrypted.split(":", -1);
                if (parts.length != 2) throw new IllegalStateException();
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
                token = new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
            } catch (Exception error) { throw new IllegalStateException("访问密钥无法解密，请在AI模型设置中重新填写"); }
        }
        return new Profile(preferences.getBoolean("enabled", false), preferences.getString("url", ""), token,
                preferences.getString("model", ""), preferences.getBoolean("disable_thinking", true), preferences.getBoolean("cue", true), preferences.getBoolean("web_search", true));
    }
    synchronized void save(Profile profile) throws Exception {
        String url = profile.baseUrl.isEmpty() && !profile.enabled ? "" : normalizeUrl(profile.baseUrl);
        if (profile.model.length() > 128 || profile.token.length() > 4096 || profile.enabled && profile.model.trim().isEmpty())
            throw new IllegalArgumentException("请填写有效的模型名和密钥");
        String encrypted = "";
        if (!profile.token.isEmpty()) {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
            encrypted = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                    + Base64.encodeToString(cipher.doFinal(profile.token.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        }
        if (!preferences.edit().putString("url", url).putString("token", encrypted).putString("model", profile.model.trim())
                .putBoolean("disable_thinking", profile.disableThinking).putBoolean("cue", profile.cue).putBoolean("web_search", profile.webSearch).putBoolean("enabled", profile.enabled).commit())
            throw new IllegalStateException("配置保存失败，请重试");
    }
    void clear() { if (!preferences.edit().clear().commit()) throw new IllegalStateException("配置清除失败"); }
    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
