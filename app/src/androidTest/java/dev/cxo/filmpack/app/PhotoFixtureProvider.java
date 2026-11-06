package dev.cxo.filmpack.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** Test APK only. Java keeps the separate provider process independent of target Kotlin classes. */
public class PhotoFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private File file(Uri uri) throws FileNotFoundException {
        String name = uri.getQueryParameter("fixture");
        if (name == null) name = uri.getLastPathSegment();
        if (name == null || !name.matches("[A-Za-z0-9._-]+") || name.startsWith(".")) {
            throw new FileNotFoundException("Invalid fixture name");
        }
        File file = new File(getContext().getCacheDir(), name);
        if (name.equals("photo-mosaic.png") && !file.exists()) {
            try (InputStream input = getContext().getAssets().open(name);
                 FileOutputStream output = new FileOutputStream(file)) {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            } catch (IOException failure) { throw new FileNotFoundException(failure.toString()); }
        }
        return file;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode));
    }
    @Override public String getType(Uri uri) {
        String identity = uri.getQueryParameter("identity");
        if ("opaque".equals(identity)) return "application/octet-stream";
        if ("mime".equals(identity)) return "image/x-sony-arw";
        if (uri.getLastPathSegment().endsWith(".RW2")) return "application/octet-stream";
        return uri.getLastPathSegment().endsWith(".jpg") ? "image/jpeg" : "image/png";
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        String[] columns = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns);
        try {
            File file = file(uri);
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                row[i] = OpenableColumns.DISPLAY_NAME.equals(columns[i]) ?
                    (uri.getQueryParameter("identity") != null ? "123456" : file.getName()) : file.length();
            }
            cursor.addRow(row);
        } catch (FileNotFoundException failure) { throw new IllegalArgumentException(failure); }
        return cursor;
    }
    @Override public int delete(Uri uri, String selection, String[] args) {
        try { return file(uri).delete() ? 1 : 0; } catch (FileNotFoundException failure) { return 0; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
