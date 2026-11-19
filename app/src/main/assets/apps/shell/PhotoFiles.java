package __PKG__;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 📷 The app's own photos (files/photos): the camera writes its picture here (only "camera.tmp", only while the camera
 * screen has the right), and a photo he chose to share is read from here. Not exported: other apps get only what one
 * share gives them.
 */
public class PhotoFiles extends ContentProvider {
    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "photos");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static Uri uri(Context c, String name) { return Uri.parse("content://" + c.getPackageName() + ".photos/" + name); }

    private File file(Uri u) throws FileNotFoundException {
        String name = u.getLastPathSegment();
        if (name == null || !(name.equals("camera.tmp") || name.matches("p[a-z0-9]{2,40}\\.jpg"))) throw new FileNotFoundException("no");
        return new File(dir(getContext()), name);
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
        File f = file(u);
        boolean write = mode != null && mode.contains("w");
        if (write && !f.getName().equals("camera.tmp")) throw new FileNotFoundException("read only");
        return ParcelFileDescriptor.open(f, write ? ParcelFileDescriptor.parseMode(mode) : ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri u, String[] proj, String sel, String[] args, String order) {
        try {
            File f = file(u);
            MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[]{f.getName(), f.length()});
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    @Override public String getType(Uri u) { return "image/jpeg"; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
