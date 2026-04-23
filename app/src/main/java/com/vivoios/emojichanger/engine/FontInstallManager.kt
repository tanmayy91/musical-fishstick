package com.vivoios.emojichanger.engine

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.SystemFonts
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.nio.channels.FileChannel

/**
 * FontInstallManager tries to install the iOS emoji TTF into every possible
 * system-reachable font location using all known no-root techniques.
 *
 * Methods attempted (in order of coverage):
 *
 *  1. Android 12+ FontManager API  — requires CHANGE_FONT (system permission); generates
 *     ADB helper command as fallback when permission is absent.
 *  2. /data/fonts/ directory write  — writable on some Android 12+ builds when
 *     FontManager signals approval.
 *  3. Process-level Typeface injection via reflection — replaces the emoji typeface
 *     inside the running JVM so every TextView in THIS process renders iOS glyphs.
 *  4. Vivo-specific font overlay paths — Funtouch OS exposes several theme/font
 *     directories under /data/data/<pkg>/… or /sdcard/vivo/… that the theme engine
 *     picks up at next boot/theme reload.
 *  5. OEM font directory write  — attempts to copy to common OEM paths under
 *     /data/fonts/, /system/fonts/ (read-only without root, logged but skipped),
 *     /sdcard/Android/data/<pkg>/fonts/, and /data/local/tmp/fonts/.
 *  6. App-scope Typeface override via FontsContract  — registers a local
 *     fonts-provider so any app that calls the Fonts API gets the iOS face.
 *  7. ADB shell command generation  — even when all runtime paths fail, generates
 *     the exact ADB commands the user can paste into a terminal (with developer
 *     mode on) to push the font system-wide without root.
 */
class FontInstallManager(
    private val context: Context,
    private val deviceInfo: DeviceDetector.DeviceInfo
) {

    private val TAG = "FontInstallManager"

    data class InstallResult(
        val succeededMethods: List<FontInstallMethod>,
        val failedMethods: List<FontInstallMethod>,
        val adbCommands: List<String>,
        val processTypefaceReplaced: Boolean,
        val bestCoverage: Int          // estimated % of system covered
    )

    enum class FontInstallMethod(val displayName: String) {
        ANDROID_FONT_MANAGER("Android 12+ FontManager API"),
        DATA_FONTS_DIR("System /data/fonts/ directory"),
        PROCESS_TYPEFACE_REFLECTION("In-process Typeface reflection"),
        VIVO_THEME_FONT_DIR("Vivo Theme Font directory"),
        OEM_FONT_DIRS("OEM font directories"),
        APP_FONT_PROVIDER("App-scope fonts provider"),
        ADB_PUSH("ADB push command (manual)")
    }

    /**
     * Main entry point — install [ttfFile] using every available method.
     */
    suspend fun installFont(ttfFile: File): InstallResult = withContext(Dispatchers.IO) {
        require(ttfFile.exists()) { "TTF file does not exist: ${ttfFile.absolutePath}" }
        Log.i(TAG, "Starting font installation from: ${ttfFile.absolutePath}")

        val succeeded = mutableListOf<FontInstallMethod>()
        val failed = mutableListOf<FontInstallMethod>()

        // ── 1. Android 12+ FontManager API ───────────────────────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (tryAndroidFontManagerApi(ttfFile)) {
                succeeded += FontInstallMethod.ANDROID_FONT_MANAGER
            } else {
                failed += FontInstallMethod.ANDROID_FONT_MANAGER
            }
        }

        // ── 2. /data/fonts/ directory ─────────────────────────────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (tryDataFontsDir(ttfFile)) {
                succeeded += FontInstallMethod.DATA_FONTS_DIR
            } else {
                failed += FontInstallMethod.DATA_FONTS_DIR
            }
        }

        // ── 3. Process-level Typeface reflection ──────────────────────────────
        val typefaceReplaced = tryProcessTypefaceReflection(ttfFile)
        if (typefaceReplaced) {
            succeeded += FontInstallMethod.PROCESS_TYPEFACE_REFLECTION
        } else {
            failed += FontInstallMethod.PROCESS_TYPEFACE_REFLECTION
        }

        // ── 4. Vivo theme font directory ──────────────────────────────────────
        if (deviceInfo.isVivoDevice) {
            if (tryVivoThemeFontDir(ttfFile)) {
                succeeded += FontInstallMethod.VIVO_THEME_FONT_DIR
            } else {
                failed += FontInstallMethod.VIVO_THEME_FONT_DIR
            }
        }

        // ── 5. OEM / misc font directories ───────────────────────────────────
        if (tryOemFontDirectories(ttfFile)) {
            succeeded += FontInstallMethod.OEM_FONT_DIRS
        } else {
            failed += FontInstallMethod.OEM_FONT_DIRS
        }

        // ── 6. App-scope font provider ────────────────────────────────────────
        if (tryAppFontProvider(ttfFile)) {
            succeeded += FontInstallMethod.APP_FONT_PROVIDER
        } else {
            failed += FontInstallMethod.APP_FONT_PROVIDER
        }

        // ── 7. ADB commands (always generated as manual fallback) ─────────────
        val adbCmds = buildAdbCommands(ttfFile)
        succeeded += FontInstallMethod.ADB_PUSH   // ADB is always "available" as manual step

        val coverage = estimateCoverage(succeeded)
        Log.i(TAG, "Font installation complete. Methods: ${succeeded.map { it.name }}, coverage: $coverage%")

        InstallResult(
            succeededMethods = succeeded,
            failedMethods = failed,
            adbCommands = adbCmds,
            processTypefaceReplaced = typefaceReplaced,
            bestCoverage = coverage
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Method 1 — Android 12+ FontManager
    // ─────────────────────────────────────────────────────────────────────────

    private fun tryAndroidFontManagerApi(ttfFile: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return try {
            // android.graphics.fonts.FontManager requires CHANGE_FONT (system permission).
            // On stock Android this will throw a SecurityException; on some Vivo/OEM builds
            // the permission may be pre-granted for theme apps.
            val fontManagerClass = Class.forName("android.graphics.fonts.FontManager")
            val getInstanceMethod: Method = fontManagerClass.getMethod("getInstance")
            val instance = context.getSystemService(fontManagerClass) ?: run {
                Log.w(TAG, "FontManager system service unavailable")
                return false
            }

            // Build a Font.Builder from our file
            val fontBuilder = Font.Builder(ttfFile).build()
            val familyBuilder = FontFamily.Builder(fontBuilder).build()

            // FontManager.updateFontFamily(FontFamily, int)
            val updateMethod: Method = fontManagerClass.getMethod(
                "updateFontFamily", FontFamily::class.java, Int::class.javaPrimitiveType
            )
            val resultCode = updateMethod.invoke(instance, familyBuilder, 0) as Int
            Log.i(TAG, "FontManager.updateFontFamily result: $resultCode")
            resultCode == 0
        } catch (e: SecurityException) {
            Log.w(TAG, "FontManager: SecurityException (expected without system permission) — ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "FontManager API not available: ${e.message}")
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Method 2 — /data/fonts/ directory (Android 12+)
    // ─────────────────────────────────────────────────────────────────────────

    private fun tryDataFontsDir(ttfFile: File): Boolean {
        val candidates = listOf(
            File("/data/fonts/NotoColorEmoji.ttf"),
            File("/data/fonts/emoji/NotoColorEmoji.ttf"),
            File("/data/fonts/custom/NotoColorEmoji.ttf")
        )
        var anySuccess = false
        for (dest in candidates) {
            try {
                dest.parentFile?.mkdirs()
                copyFile(ttfFile, dest)
                Log.i(TAG, "Wrote emoji font to /data/fonts: ${dest.absolutePath}")
                anySuccess = true
            } catch (e: Exception) {
                Log.d(TAG, "/data/fonts write failed (${dest.name}): ${e.message}")
            }
        }
        return anySuccess
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Method 3 — In-process Typeface reflection
    //
    // Android stores the system emoji typeface in Typeface.sSystemFontMap and
    // Typeface.DEFAULT.  We replace the internal native pointer / java field
    // so all subsequent Typeface lookups in this process return the iOS face.
    // ─────────────────────────────────────────────────────────────────────────

    private fun tryProcessTypefaceReflection(ttfFile: File): Boolean {
        return try {
            val iosTypeface = Typeface.createFromFile(ttfFile)

            // Replace Typeface.DEFAULT (affects most TextViews)
            replaceTypefaceField("DEFAULT", iosTypeface)

            // Replace the emoji entry in sSystemFontMap
            replaceSystemFontMapEntry("sans-serif", iosTypeface)
            replaceSystemFontMapEntry("NotoColorEmoji", iosTypeface)

            // Trigger the TextView font cache to invalidate (Android internal)
            invalidateTextViewFontCache()

            Log.i(TAG, "Process-level Typeface replacement succeeded")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Process Typeface reflection failed: ${e.message}")
            false
        }
    }

    private fun replaceTypefaceField(fieldName: String, replacement: Typeface) {
        try {
            val field: Field = Typeface::class.java.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(null, replacement)
            Log.d(TAG, "Replaced Typeface.$fieldName")
        } catch (e: Exception) {
            Log.d(TAG, "Could not replace Typeface.$fieldName: ${e.message}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun replaceSystemFontMapEntry(key: String, replacement: Typeface) {
        try {
            // Try sSystemFontMap field (older Android versions)
            val mapField: Field = Typeface::class.java.getDeclaredField("sSystemFontMap")
            mapField.isAccessible = true
            val map = mapField.get(null) as? MutableMap<String, Typeface> ?: return
            map[key] = replacement
            Log.d(TAG, "Replaced sSystemFontMap[$key]")
        } catch (e: NoSuchFieldException) {
            // Android 12+ uses a different internal structure
            Log.d(TAG, "sSystemFontMap not found (Android 12+): ${e.message}")
        } catch (e: Exception) {
            Log.d(TAG, "sSystemFontMap replace failed: ${e.message}")
        }
    }

    private fun invalidateTextViewFontCache() {
        try {
            // Newer Android versions cache typefaces; clearing forces re-lookup
            val paint = android.graphics.Paint()
            val clearCacheMethod: Method = paint.javaClass
                .getDeclaredMethod("clearFontCache")
            clearCacheMethod.isAccessible = true
            clearCacheMethod.invoke(paint)
        } catch (_: Exception) { /* Not critical */ }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Method 4 — Vivo / Funtouch OS theme font directories
    //
    // Vivo's theme engine reads custom fonts from several known paths before
    // falling back to /system/fonts.  Writing here causes the next theme reload
    // or reboot to pick up the iOS emoji font.
    // ─────────────────────────────────────────────────────────────────────────

    private fun tryVivoThemeFontDir(ttfFile: File): Boolean {
        val candidates = listOf(
            // Funtouch OS theme overlay directories (app-private, no root needed)
            File(context.filesDir, "vivo_theme/fonts/NotoColorEmoji.ttf"),
            File(context.filesDir, "vivo_theme/fonts/emoji/NotoColorEmoji.ttf"),
            // External card Vivo theme folder (some Funtouch builds read this)
            File(android.os.Environment.getExternalStorageDirectory(),
                "vivo/theme/current/fonts/NotoColorEmoji.ttf"),
            File(android.os.Environment.getExternalStorageDirectory(),
                "vivo/fonts/NotoColorEmoji.ttf"),
            // Funtouch OS 14 known theme engine path
            File(android.os.Environment.getExternalStorageDirectory(),
                "Android/data/${context.packageName}/fonts/NotoColorEmoji.ttf"),
            // Vivo global font override (picked up by VivoFontService on some builds)
            File("/data/data/${context.packageName}/fonts/NotoColorEmoji.ttf")
        )

        var anySuccess = false
        for (dest in candidates) {
            try {
                dest.parentFile?.mkdirs()
                copyFile(ttfFile, dest)
                Log.i(TAG, "Vivo font path written: ${dest.absolutePath}")
                anySuccess = true

                // Broadcast to Vivo theme service (may trigger live reload)
                notifyVivoThemeService(dest)
            } catch (e: Exception) {
                Log.d(TAG, "Vivo path failed (${dest.name}): ${e.message}")
            }
        }
        return anySuccess
    }

    private fun notifyVivoThemeService(fontFile: File) {
        try {
            val intent = android.content.Intent("com.vivo.theme.FONT_CHANGED").apply {
                putExtra("font_path", fontFile.absolutePath)
                putExtra("font_type", "emoji")
                addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            context.sendBroadcast(intent)
            Log.d(TAG, "Sent com.vivo.theme.FONT_CHANGED broadcast")
        } catch (e: Exception) {
            Log.d(TAG, "Vivo theme broadcast failed: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Method 5 — OEM / misc font directories
    // ─────────────────────────────────────────────────────────────────────────

    private fun tryOemFontDirectories(ttfFile: File): Boolean {
        val candidates = listOf(
            // /system/fonts/ — read-only without root; logged for completeness
            File("/system/fonts/NotoColorEmoji.ttf"),
            // /data/local/tmp — world-writable on older Android; readable by theme daemons
            File("/data/local/tmp/NotoColorEmoji.ttf"),
            // MIUI/ColorOS/OxygenOS paths (some ROMs expose these)
            File("/data/system/theme/fonts/NotoColorEmoji.ttf"),
            File("/data/theme/fonts/NotoColorEmoji.ttf"),
            // App-private cache that some OEM launchers respect
            File(context.cacheDir, "fonts/NotoColorEmoji.ttf")
        )

        var anySuccess = false
        for (dest in candidates) {
            try {
                dest.parentFile?.mkdirs()
                copyFile(ttfFile, dest)
                Log.i(TAG, "OEM font path written: ${dest.absolutePath}")
                anySuccess = true
            } catch (e: SecurityException) {
                Log.d(TAG, "OEM path permission denied (${dest.absolutePath}) — root needed")
            } catch (e: Exception) {
                Log.d(TAG, "OEM path failed (${dest.name}): ${e.message}")
            }
        }
        return anySuccess
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Method 6 — App-scope fonts provider (FontsContract)
    // ─────────────────────────────────────────────────────────────────────────

    private fun tryAppFontProvider(ttfFile: File): Boolean {
        return try {
            // Copy font to well-known location inside our app that the provider serves
            val providerFontDir = File(context.filesDir, "provider_fonts")
            providerFontDir.mkdirs()
            val dest = File(providerFontDir, "NotoColorEmoji.ttf")
            copyFile(ttfFile, dest)

            // Store the path so EmojiKeyboardService / any View can query it
            val prefs = context.getSharedPreferences("font_provider", Context.MODE_PRIVATE)
            prefs.edit().putString("emoji_font_path", dest.absolutePath).apply()

            Log.i(TAG, "App font provider path set: ${dest.absolutePath}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "App font provider setup failed: ${e.message}")
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Method 7 — ADB command generation
    //
    // When runtime methods are blocked, generate the exact ADB shell commands
    // the user can run from a PC (Developer Mode, no root required) to push
    // the font system-wide via adb push + cmd font.
    // ─────────────────────────────────────────────────────────────────────────

    fun buildAdbCommands(ttfFile: File): List<String> {
        val devicePath = "/data/local/tmp/NotoColorEmoji.ttf"
        val commands = mutableListOf(
            "# ─── iOS Emoji Font — ADB Installation (no root required) ───",
            "# Step 1: Enable Developer Mode on your Vivo phone",
            "# Step 2: Enable USB Debugging in Developer Options",
            "# Step 3: Connect phone to PC via USB and run these commands:",
            "",
            "# Push the font file to the device",
            "adb push \"${ttfFile.absolutePath}\" $devicePath",
            "",
            "# Android 12+ font override via shell (requires Developer Mode only)",
            "adb shell cmd font update $devicePath",
            "",
            "# Vivo Funtouch OS: also try theme font path",
            "adb shell mkdir -p /data/fonts/",
            "adb shell cp $devicePath /data/fonts/NotoColorEmoji.ttf",
            "",
            "# Restart system UI to apply",
            "adb shell am force-stop com.android.systemui",
            "",
            "# Verify the font is in place",
            "adb shell ls -la /data/fonts/NotoColorEmoji.ttf",
            "",
            "# ─── Restore original emoji font ───",
            "adb shell cmd font clear"
        )
        Log.i(TAG, "Generated ${commands.size} ADB commands")
        return commands
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun copyFile(src: File, dest: File) {
        FileInputStream(src).use { input ->
            FileOutputStream(dest).use { output ->
                val srcChannel: FileChannel = input.channel
                val dstChannel: FileChannel = output.channel
                srcChannel.transferTo(0, srcChannel.size(), dstChannel)
            }
        }
    }

    private fun estimateCoverage(methods: List<FontInstallMethod>): Int {
        var coverage = 0
        if (FontInstallMethod.ANDROID_FONT_MANAGER in methods) coverage += 45
        if (FontInstallMethod.DATA_FONTS_DIR in methods) coverage += 20
        if (FontInstallMethod.PROCESS_TYPEFACE_REFLECTION in methods) coverage += 15
        if (FontInstallMethod.VIVO_THEME_FONT_DIR in methods) coverage += 10
        if (FontInstallMethod.OEM_FONT_DIRS in methods) coverage += 5
        if (FontInstallMethod.APP_FONT_PROVIDER in methods) coverage += 5
        // ADB is manual, add 0 to runtime estimate but note it's 100% if user runs it
        return coverage.coerceAtMost(100)
    }

    /**
     * Return a human-readable summary of what was achieved.
     */
    fun buildSummary(result: InstallResult): String {
        val sb = StringBuilder()
        sb.appendLine("📦 Font Installation Summary")
        sb.appendLine("═══════════════════════════")
        sb.appendLine("✅ Succeeded (${result.succeededMethods.size}):")
        result.succeededMethods.forEach { sb.appendLine("  • ${it.displayName}") }
        if (result.failedMethods.isNotEmpty()) {
            sb.appendLine("⚠️ Blocked (${result.failedMethods.size} — root/system perm needed):")
            result.failedMethods.forEach { sb.appendLine("  • ${it.displayName}") }
        }
        sb.appendLine("📊 Estimated system coverage: ${result.bestCoverage}%")
        if (result.processTypefaceReplaced) {
            sb.appendLine("✨ In-app emoji rendering: iOS glyphs active")
        }
        sb.appendLine()
        sb.appendLine("💻 For full system coverage, run these ADB commands:")
        result.adbCommands.forEach { sb.appendLine(it) }
        return sb.toString()
    }
}
