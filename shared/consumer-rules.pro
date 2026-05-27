# =============================================================================
# :shared  —  consumer ProGuard / R8 rules
#
# These rules are automatically merged into the ProGuard pass of every app
# that depends on :shared (both :mobile and :automotive).  You do not need to
# duplicate them in the app-level proguard-rules.pro files.
# =============================================================================

# ── MediaBrowserServiceCompat ─────────────────────────────────────────────────
# MusicService (and the stub MyMusicService) are loaded by the Android OS via
# the AndroidManifest <service> entry.  R8 cannot see that reference, so we
# keep all subclasses explicitly.
-keep public class * extends androidx.media.MediaBrowserServiceCompat

# ── MediaSessionCompat.Callback ───────────────────────────────────────────────
# The session callback is an anonymous object expression — R8 would otherwise
# strip the overridden onPlay/onPause/… methods it cannot trace.
-keep class * extends android.support.v4.media.session.MediaSessionCompat$Callback { *; }

# ── MediaBrowserCompat callbacks ──────────────────────────────────────────────
# Connection and subscription callbacks are inner anonymous classes whose
# overridden methods are called by the framework via reflection.
-keep class * extends android.support.v4.media.MediaBrowserCompat$ConnectionCallback  { *; }
-keep class * extends android.support.v4.media.MediaBrowserCompat$SubscriptionCallback { *; }

# ── MediaControllerCompat.Callback ───────────────────────────────────────────
-keep class * extends android.support.v4.media.session.MediaControllerCompat$Callback { *; }

# ── Shared data classes ───────────────────────────────────────────────────────
# Song and Playlist are passed through MediaDescriptionCompat extras and Bundle;
# keep all members so R8 doesn't inline or rename fields accessed at runtime.
-keep class com.example.automotivemediaserviceprranit.shared.Song     { *; }
-keep class com.example.automotivemediaserviceprranit.shared.Playlist  { *; }
-keep class com.example.automotivemediaserviceprranit.shared.MusicScanner { *; }

# ── Kotlin metadata ───────────────────────────────────────────────────────────
-keepattributes *Annotation*, Signature, Exceptions
