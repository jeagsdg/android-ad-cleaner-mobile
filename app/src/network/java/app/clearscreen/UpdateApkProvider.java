package app.clearscreen;

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

/** Grants the system installer read access only to a prepared internal APK. */
public final class UpdateApkProvider extends ContentProvider {
    static final String APK_MIME_TYPE = "application/vnd.android.package-archive";

    private static String authority(Context context) {
        return context.getPackageName() + ".release-apk";
    }

    static Uri apkUri(Context context, long id) {
        return new Uri.Builder().scheme("content").authority(authority(context))
                .appendPath(Long.toString(id)).build();
    }

    static File apkFile(Context context, long id) {
        return new File(new File(context.getCacheDir(), "verified-release-apks"), id + ".apk");
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) { return APK_MIME_TYPE; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Update APKs are read-only");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        File apk;
        try { apk = file(uri); }
        catch (FileNotFoundException failure) { return null; }
        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor result = new MatrixCursor(columns, 1);
        MatrixCursor.RowBuilder row = result.newRow();
        for (String column : columns) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) row.add("ClearScreen.apk");
            else if (OpenableColumns.SIZE.equals(column)) row.add(apk.length());
            else row.add(null);
        }
        return result;
    }

    private File file(Uri uri) throws FileNotFoundException {
        if (!authority(getContext()).equals(uri.getAuthority()) || uri.getPathSegments().size() != 1)
            throw new FileNotFoundException("Invalid update APK URI");
        try {
            long id = Long.parseLong(uri.getLastPathSegment());
            if (id < 0) throw new NumberFormatException();
            File apk = apkFile(getContext(), id);
            if (apk.isFile()) return apk;
        } catch (NumberFormatException ignored) { }
        throw new FileNotFoundException("Update APK unavailable");
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
