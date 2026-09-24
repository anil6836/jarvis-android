package com.anil.jarvis;

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

/** Gives the camera app one private file to save the photo into (no extra libraries needed). */
public class PhotoProvider extends ContentProvider {
    static final String AUTHORITY = "com.anil.jarvis.photos";

    static Uri uri() { return Uri.parse("content://" + AUTHORITY + "/capture.jpg"); }

    static File file(Context c) { return new File(c.getCacheDir(), "capture.jpg"); }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = file(getContext());
        int m = ParcelFileDescriptor.parseMode(mode);
        if (mode.contains("w")) m |= ParcelFileDescriptor.MODE_CREATE;
        return ParcelFileDescriptor.open(f, m);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f = file(getContext());
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{f.getName(), f.length()});
        return c;
    }

    @Override public String getType(Uri uri) { return "image/jpeg"; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
