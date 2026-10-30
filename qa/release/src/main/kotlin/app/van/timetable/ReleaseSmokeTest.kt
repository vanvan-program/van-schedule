package app.van.timetable

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Black-box checks: no references to app classes, so these also run on the R8 APK. */
class ReleaseSmokeTest {
    private fun persistentState(context: Context): JSONObject {
        val rows = JSONArray()
        SQLiteDatabase.openDatabase(context.getDatabasePath("van.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT id, payload FROM schedules ORDER BY id", null).use { cursor ->
                while (cursor.moveToNext()) rows.put(JSONArray().put(cursor.getString(0)).put(cursor.getString(1)))
            }
        }
        val preferences = File(context.filesDir, "datastore/van_settings.preferences_pb").readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(preferences).joinToString("") { "%02x".format(it) }
        return JSONObject().put("rows", rows).put("preferencesSha256", digest)
    }

    @Test fun captureUpgradeState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        assertEquals("Capture must run against the original APK", "0.3.0",
            context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        val snapshot = persistentState(context)
        assertTrue("Upgrade fixture must contain existing schedules", snapshot.getJSONArray("rows").length() > 0)
        File(context.getExternalFilesDir(null), "release-upgrade-before.json").writeText(snapshot.toString())
    }

    @Test fun optimizedUpgradeKeepsAllSchedulePayloadsAndSettingsAndWidgetProviders() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val before = JSONObject(File(context.getExternalFilesDir(null), "release-upgrade-before.json").readText())
        val after = persistentState(context)
        assertEquals(before.getJSONArray("rows").toString(), after.getJSONArray("rows").toString())
        assertEquals(before.getString("preferencesSha256"), after.getString("preferencesSha256"))
        val manager = AppWidgetManager.getInstance(context)
        for (provider in listOf("DualWidget", "TodayWidget", "NextWidget")) {
            val component = ComponentName(context.packageName, "app.van.timetable.widget.$provider")
            assertTrue("Widget provider must retain its persisted component name: $provider",
                manager.installedProviders.any { it.provider == component })
            context.sendBroadcast(Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .setComponent(component).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf()))
        }
    }

    @Test fun optimizedApkRunsBundledChineseOcrThroughJni() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "release-ocr-check").apply { mkdirs() }
        val tessdata = File(root, "tessdata").apply { mkdirs() }
        for (language in listOf("chi_sim", "eng")) {
            context.assets.open("tessdata/$language.traineddata").use { input ->
                File(tessdata, "$language.traineddata").outputStream().use { input.copyTo(it) }
            }
        }
        val bitmap = Bitmap.createBitmap(1800, 1400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 64f }
        listOf("课程名称：高等数学", "星期一", "第1-2节", "周次：1-16周", "Teacher: Chen", "Room: A101")
            .forEachIndexed { i, line -> canvas.drawText(line, 96f, 320f + i * 120f, paint) }
        // Resolve the exact Java/JNI bridge from the installed R8 APK, not this runner.
        val type = Class.forName("com.googlecode.tesseract.android.TessBaseAPI", true, context.classLoader)
        val tess = type.getConstructor().newInstance()
        try {
            assertEquals(true, type.getMethod("init", String::class.java, String::class.java, Int::class.javaPrimitiveType)
                .invoke(tess, root.absolutePath, "chi_sim+eng", 1))
            type.getMethod("setPageSegMode", Int::class.javaPrimitiveType).invoke(tess, 11)
            type.getMethod("setImage", Bitmap::class.java).invoke(tess, bitmap)
            val recognized = type.getMethod("getUTF8Text").invoke(tess) as String
            File(context.getExternalFilesDir(null), "release-ocr-recognition.txt").writeText(recognized)
            assertTrue("The installed release APK must recognize Chinese: $recognized", recognized.contains("高等"))
            assertTrue("The installed release APK must recognize English: $recognized", recognized.contains("A101"))
        } finally {
            type.getMethod("recycle").invoke(tess)
            bitmap.recycle()
        }
    }
}
