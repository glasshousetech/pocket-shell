package network.ght.pocketshell;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * Synthetic content provider packaged only in the instrumentation APK.
 * Android starts it outside the target app, so use platform/Java APIs only:
 * instrumentation libraries deduplicated into the target APK are not available here.
 */
public final class TransferFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private File file(Uri uri) {
        List<String> parts = uri.getPathSegments();
        if (parts.size() != 2 || !parts.get(0).matches("[a-f0-9-]{36}")
                || !("upload α.bin".equals(parts.get(1)) || "download α.bin".equals(parts.get(1)))) {
            throw new IllegalArgumentException("Unknown synthetic fixture");
        }
        File root = new File(getContext().getCacheDir(), "transfer-fixtures/" + parts.get(0));
        if (!root.isDirectory() && !root.mkdirs()) throw new IllegalStateException("Cannot create fixture directory");
        return new File(root, parts.get(1));
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File file = file(uri);
        String[] columns = projection != null ? projection
                : new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = file.getName();
            else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = file.length();
        }
        MatrixCursor cursor = new MatrixCursor(columns);
        cursor.addRow(values);
        return cursor;
    }
    @Override public String getType(Uri uri) { return "application/octet-stream"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode));
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { return file(uri).delete() ? 1 : 0; }
}
