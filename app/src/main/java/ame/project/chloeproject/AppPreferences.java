package ame.project.chloeproject;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.firebase.crashlytics.FirebaseCrashlytics;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Pengelola konfigurasi berbasis file JSON untuk aplikasi Chloe Project.
 * Menggantikan SharedPreferences agar lebih stabil dan terhindar dari error saat update aplikasi.
 */
public class AppPreferences {

    private static final String FILE_NAME = "config.json";
    private static final String PREF_NAME = "cfg"; // Digunakan untuk migrasi otomatis dari SharedPreferences lama

    private static final String KEY_FRONT = "front";
    private static final String KEY_PREVIEW = "preview";
    private static final String KEY_BG_INDEX = "bg";
    private static final String KEY_BG_MODE = "bg_mode";
    private static final String KEY_POS_X = "posX";
    private static final String KEY_POS_Y = "posY";
    private static final String KEY_SENSITIVITY = "sens";
    private static final String KEY_SIZE = "size";
    private static final String KEY_FADE_IN = "in";
    private static final String KEY_FADE_OUT = "out";

    public static final int DEFAULT_SENSITIVITY = 50;
    public static final int DEFAULT_SIZE = 80;
    public static final int DEFAULT_FADE_IN = 120;
    public static final int DEFAULT_FADE_OUT = 250;

    private final File configFile;
    private JSONObject jsonConfig;

    public AppPreferences(Context context) {
        Context appContext = context.getApplicationContext();
        this.configFile = new File(appContext.getFilesDir(), FILE_NAME);
        this.jsonConfig = new JSONObject();

        loadConfig(appContext);
    }

    private synchronized void loadConfig(Context appContext) {
        if (configFile.exists()) {
            try (FileInputStream fis = new FileInputStream(configFile);
                 InputStreamReader isr = new InputStreamReader(fis, StandardCharsets.UTF_8)) {
                StringBuilder builder = new StringBuilder();
                char[] buffer = new char[1024];
                int read;
                while ((read = isr.read(buffer)) != -1) {
                    builder.append(buffer, 0, read);
                }
                this.jsonConfig = new JSONObject(builder.toString());
                return;
            } catch (Exception e) {
                FirebaseCrashlytics.getInstance().recordException(e);
            }
        }

        // Jika file JSON belum ada atau gagal dibaca, lakukan migrasi dari SharedPreferences
        migrateFromSharedPreferences(appContext);
    }

    private void migrateFromSharedPreferences(Context appContext) {
        SharedPreferences oldPrefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        try {
            jsonConfig.put(KEY_FRONT, oldPrefs.getBoolean(KEY_FRONT, true));
            jsonConfig.put(KEY_PREVIEW, oldPrefs.getBoolean(KEY_PREVIEW, false));
            jsonConfig.put(KEY_BG_INDEX, oldPrefs.getInt(KEY_BG_INDEX, 0));
            jsonConfig.put(KEY_BG_MODE, oldPrefs.getInt(KEY_BG_MODE, 0));
            jsonConfig.put(KEY_POS_X, (double) oldPrefs.getFloat(KEY_POS_X, 0f));
            jsonConfig.put(KEY_POS_Y, (double) oldPrefs.getFloat(KEY_POS_Y, 0f));
            jsonConfig.put(KEY_SENSITIVITY, oldPrefs.getInt(KEY_SENSITIVITY, DEFAULT_SENSITIVITY));
            jsonConfig.put(KEY_SIZE, oldPrefs.getInt(KEY_SIZE, DEFAULT_SIZE));
            jsonConfig.put(KEY_FADE_IN, oldPrefs.getInt(KEY_FADE_IN, DEFAULT_FADE_IN));
            jsonConfig.put(KEY_FADE_OUT, oldPrefs.getInt(KEY_FADE_OUT, DEFAULT_FADE_OUT));
        } catch (JSONException e) {
            FirebaseCrashlytics.getInstance().recordException(e);
        }
        saveConfig();
    }

    private synchronized void saveConfig() {
        File tempFile = new File(configFile.getParent(), FILE_NAME + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tempFile);
             OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            osw.write(jsonConfig.toString(2));
            osw.flush();
            if (tempFile.exists()) {
                if (configFile.exists()) {
                    configFile.delete();
                }
                tempFile.renameTo(configFile);
            }
        } catch (Exception e) {
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }

    public boolean isUseFrontCamera() {
        return jsonConfig.optBoolean(KEY_FRONT, true);
    }

    public void setUseFrontCamera(boolean useFront) {
        try {
            jsonConfig.put(KEY_FRONT, useFront);
            saveConfig();
        } catch (JSONException e) {
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }

    public boolean isShowCameraPreview() {
        return jsonConfig.optBoolean(KEY_PREVIEW, false);
    }

    public void setShowCameraPreview(boolean showPreview) {
        try {
            jsonConfig.put(KEY_PREVIEW, showPreview);
            saveConfig();
        } catch (JSONException e) {
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }

    public int getBgIndex() {
        return jsonConfig.optInt(KEY_BG_INDEX, 0);
    }

    public void setBgIndex(int index) {
        try {
            jsonConfig.put(KEY_BG_INDEX, index);
            saveConfig();
        } catch (JSONException e) {
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }

    public int getBgMode() {
        return jsonConfig.optInt(KEY_BG_MODE, 0);
    }

    public void setBgMode(int mode) {
        try {
            jsonConfig.put(KEY_BG_MODE, mode);
            saveConfig();
        } catch (JSONException e) {
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }

    public float getPosX() {
        return (float) jsonConfig.optDouble(KEY_POS_X, 0.0);
    }

    public float getPosY() {
        return (float) jsonConfig.optDouble(KEY_POS_Y, 0.0);
    }

    public void setPosition(float x, float y) {
        try {
            jsonConfig.put(KEY_POS_X, (double) x);
            jsonConfig.put(KEY_POS_Y, (double) y);
            saveConfig();
        } catch (JSONException e) {
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }

    public int getSensitivity() {
        return jsonConfig.optInt(KEY_SENSITIVITY, DEFAULT_SENSITIVITY);
    }

    public void setSensitivity(int sens) {
        try {
            jsonConfig.put(KEY_SENSITIVITY, sens);
            saveConfig();
        } catch (JSONException e) {
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }

    public int getSize() {
        return jsonConfig.optInt(KEY_SIZE, DEFAULT_SIZE);
    }

    public void setSize(int size) {
        try {
            jsonConfig.put(KEY_SIZE, size);
            saveConfig();
        } catch (JSONException e) {
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }

    public int getFadeIn() {
        return jsonConfig.optInt(KEY_FADE_IN, DEFAULT_FADE_IN);
    }

    public void setFadeIn(int duration) {
        try {
            jsonConfig.put(KEY_FADE_IN, duration);
            saveConfig();
        } catch (JSONException e) {
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }

    public int getFadeOut() {
        return jsonConfig.optInt(KEY_FADE_OUT, DEFAULT_FADE_OUT);
    }

    public void setFadeOut(int duration) {
        try {
            jsonConfig.put(KEY_FADE_OUT, duration);
            saveConfig();
        } catch (JSONException e) {
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }
}
