# =============================================================================
# :mobile  —  R8 / ProGuard rules  (release build only)
#
# Rules for the :shared module's media classes are injected automatically from
# shared/consumer-rules.pro — no need to repeat them here.
# =============================================================================

# ── Stack traces ──────────────────────────────────────────────────────────────
# Keep file names and line numbers so crash reports (Crashlytics, Play Console)
# point to real source locations.  The original file name is hidden from the
# APK (only the mapping file retains it) via renamesourcefileattribute.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# ── Kotlin metadata ───────────────────────────────────────────────────────────
-keepattributes *Annotation*, Signature, Exceptions

# ── RecyclerView ─────────────────────────────────────────────────────────────
# ViewHolder subclasses are accessed reflectively by the framework in some
# RecyclerView versions; keep their constructors.
-keepclassmembers class * extends androidx.recyclerview.widget.RecyclerView$ViewHolder {
    public <init>(android.view.View);
}

# ── Material Components ───────────────────────────────────────────────────────
# MaterialCardView and other Material widgets inflate via reflection from XML.
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# ── Suppress known benign warnings ────────────────────────────────────────────
# These come from legacy android.support shims inside androidx.media 1.x;
# they are harmless because the shims are never called on API 28+.
-dontwarn android.support.**
