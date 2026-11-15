package com.zyvo.code.adb;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Extracts the bundled adb key assets (adbkey + adbcert.pem) from the APK into
 * the app's private dir, so the wireless-debugging pair/connect always uses the
 * same key. Ported from the Apache-2.0 android-wireless-debug project; the
 * Shizuku-server parts of the original were dropped — only the asset bootstrap
 * remains.
 */
public class ShizukuBootstrap {
    private static final String TAG = "ShizukuBootstrap";
    public static final String ASSET_DIR = "shizuku";
    public static final String ADB_KEY = "adbkey";
    public static final String ADB_CERT = "adbcert.pem";

    /** 释放内置资产到 files/shizuku/（幂等，按文件大小判断是否需要重新释放）。 */
    public static void ensureAssets(Context ctx) {
        try {
            File dir = new File(ctx.getFilesDir(), ASSET_DIR);
            if (!dir.exists() && !dir.mkdirs()) return;
            copyIfNeeded(ctx, ADB_KEY, new File(dir, ADB_KEY));
            copyIfNeeded(ctx, ADB_CERT, new File(dir, ADB_CERT));
            Log.d(TAG, "assets ready at " + dir.getAbsolutePath());
        } catch (Throwable t) {
            Log.e(TAG, "ensureAssets failed", t);
        }
    }

    private static void copyIfNeeded(Context ctx, String asset, File dst) throws Exception {
        if (dst.exists() && dst.length() > 0) {
            InputStream cur = ctx.getAssets().open(ASSET_DIR + "/" + asset);
            int curLen = cur.available();
            cur.close();
            if (dst.length() == curLen) return;
        }
        InputStream in = ctx.getAssets().open(ASSET_DIR + "/" + asset);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
        out.close();
        in.close();
        dst.setExecutable(true, true);
        Log.d(TAG, "asset released: " + asset);
    }
}
