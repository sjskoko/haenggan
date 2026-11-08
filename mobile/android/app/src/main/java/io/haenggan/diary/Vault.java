package io.haenggan.diary;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Private app storage, encrypted by an Android Keystore key. No backup or WebView token access. */
final class Vault {
    private final AtomicFile file;
    private final SecretKey key;
    private JSONObject data;
    Vault(Context context) throws Exception {
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "haenggan-v1.bin"));
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        String alias = "haenggan-v1";
        if (!ks.containsAlias(alias)) {
            KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            gen.generateKey();
        }
        key = (SecretKey)ks.getKey(alias, null);
        if (!file.getBaseFile().exists()) { data = new JSONObject(); return; }
        byte[] raw = file.readFully();
        if (raw.length < 29) throw new IOException("보관된 기록을 열 수 없어요.");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOf(raw, 12)));
        data = new JSONObject(new String(c.doFinal(Arrays.copyOfRange(raw, 12, raw.length)), StandardCharsets.UTF_8));
    }
    synchronized JSONObject read() throws Exception { return new JSONObject(data.toString()); }
    synchronized void write(JSONObject updated) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = c.doFinal(updated.toString().getBytes(StandardCharsets.UTF_8));
        FileOutputStream out = null;
        try { out = file.startWrite(); out.write(c.getIV()); out.write(encrypted); file.finishWrite(out); }
        catch (Exception e) { if(out != null) file.failWrite(out); throw e; }
        data = new JSONObject(updated.toString());
    }
}
