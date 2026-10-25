package dev.calvault;
import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import java.nio.charset.StandardCharsets;
import android.content.ContentValues;
import android.content.Intent;
import android.provider.MediaStore;
import android.graphics.Bitmap;

/** Independent test APK process; uses no target-app runtime classes. */
public class FixtureSetupActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        for (String name : new String[]{"move-a.txt", "move-b.txt", "readonly-c.txt"}) {
            Bundle args = new Bundle();
            args.putByteArray("bytes", ("Private synthetic " + name).getBytes(StandardCharsets.UTF_8));
            getContentResolver().call(Uri.parse("content://dev.calvault.test.documents"), "seed", name, args);
        }
        if (getIntent().getBooleanExtra("gallery", false)) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "CalVault synthetic gallery.png");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CalVaultQA/");
            Uri media = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(0xff789abc);
            try (java.io.OutputStream out = getContentResolver().openOutputStream(media)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
            catch (java.io.IOException e) { throw new IllegalStateException(e); }
            bitmap.recycle();
            grantUriPermission("dev.calvault", media, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Bundle args = new Bundle(); args.putByteArray("bytes", media.toString().getBytes(StandardCharsets.UTF_8));
            getContentResolver().call(Uri.parse("content://dev.calvault.test.documents"), "seed", "media-uri.txt", args);
        }
        finish();
    }
}
