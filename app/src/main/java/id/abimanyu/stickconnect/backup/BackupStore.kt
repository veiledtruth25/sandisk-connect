package id.abimanyu.stickconnect.backup

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build

/** Catatan file yang sudah ter-backup, supaya tidak di-upload dua kali. */
class BackupDb(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "backup.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE done (key TEXT PRIMARY KEY, remote TEXT, at INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun doneKeys(): Set<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery("SELECT key FROM done", null).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    fun markDone(key: String, remote: String) {
        writableDatabase.insertWithOnConflict(
            "done", null,
            ContentValues().apply {
                put("key", key)
                put("remote", remote)
                put("at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM done", null).use { c ->
        if (c.moveToFirst()) c.getInt(0) else 0
    }

    fun reset() {
        writableDatabase.delete("done", null, null)
    }
}

/** Pengaturan backup. */
object BackupSettings {
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("backup", Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = p(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("enabled", v).apply()

    fun buckets(ctx: Context): Set<String> = p(ctx).getStringSet("buckets", null)?.toSet() ?: setOf("Camera")
    fun setBuckets(ctx: Context, v: Set<String>) = p(ctx).edit().putStringSet("buckets", v).apply()

    fun target(ctx: Context): String =
        p(ctx).getString("target", null) ?: "/Backup/${Build.MANUFACTURER} ${Build.MODEL}".replace(Regex("[\\\\:*?\"<>|]"), "_")

    fun setTarget(ctx: Context, v: String) = p(ctx).edit().putString("target", v).apply()

    fun lastResult(ctx: Context): String? = p(ctx).getString("last_result", null)
    fun setLastResult(ctx: Context, v: String) = p(ctx).edit().putString("last_result", v).apply()
}
