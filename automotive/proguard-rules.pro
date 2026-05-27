# =============================================================================
# :automotive  —  R8 / ProGuard rules  (release build only)
#
# Rules for the :shared module's media classes are injected automatically from
# shared/consumer-rules.pro — no need to repeat them here.
# =============================================================================

# ── Stack traces ──────────────────────────────────────────────────────────────
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# ── Kotlin metadata ───────────────────────────────────────────────────────────
-keepattributes *Annotation*, Signature, Exceptions

# ── Material Components ───────────────────────────────────────────────────────
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# ── Suppress known benign warnings ────────────────────────────────────────────
-dontwarn android.support.**
