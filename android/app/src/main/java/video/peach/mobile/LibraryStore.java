package video.peach.mobile;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;
import video.peach.mobile.rpc.CloudDriveFile;

/** 安卓媒体库拥有独立存储，不读取或修改桌面版 ledger。 */
public final class LibraryStore extends SQLiteOpenHelper {
    LibraryStore(Context context) { super(context.getApplicationContext(), "mobile-library.db", null, 1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE media(path TEXT PRIMARY KEY,name TEXT NOT NULL,size INTEGER NOT NULL,position INTEGER NOT NULL DEFAULT 0,played INTEGER NOT NULL DEFAULT 0)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { throw new IllegalStateException("不支持的媒体库版本"); }
    void add(CloudDriveFile file) {
        ContentValues row = new ContentValues();
        row.put("path", file.getFullPathName()); row.put("name", file.getName()); row.put("size", file.getSize());
        getWritableDatabase().insertWithOnConflict("media", null, row, SQLiteDatabase.CONFLICT_IGNORE);
        getWritableDatabase().update("media", row, "path=?", new String[]{file.getFullPathName()});
    }
    void remove(String path) { getWritableDatabase().delete("media", "path=?", new String[]{path}); }
    boolean contains(String path) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT 1 FROM media WHERE path=?", new String[]{path})) { return cursor.moveToFirst(); }
    }
    List<CloudDriveFile> all() {
        List<CloudDriveFile> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT path,name,size FROM media ORDER BY played DESC,name COLLATE NOCASE", null)) {
            while (cursor.moveToNext()) result.add(CloudDriveFile.newBuilder().setFullPathName(cursor.getString(0)).setName(cursor.getString(1)).setSize(cursor.getLong(2)).build());
        }
        return result;
    }
    int position(String path) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT position FROM media WHERE path=?", new String[]{path})) { return cursor.moveToFirst() ? cursor.getInt(0) : 0; }
    }
    void progress(String path, int position) {
        ContentValues values = new ContentValues(); values.put("position", Math.max(0, position)); values.put("played", System.currentTimeMillis());
        getWritableDatabase().update("media", values, "path=?", new String[]{path});
    }
}
