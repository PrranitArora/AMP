package com.example.automotivemediaserviceprranit

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.SeekBar
import android.widget.TextView
import android.view.View
import androidx.appcompat.app.AppCompatActivity

/**
 * Settings screen — lets the user pick the base hue for the playlist card gradient.
 *
 * The chosen hue is stored in SharedPreferences under [PREF_FILE] / [KEY_PLAYLIST_HUE].
 * [MainActivity] reads this value when it connects to MusicService and whenever it
 * returns from this activity, then forwards it to [PlaylistAdapter.baseHue].
 *
 * Color model
 * ───────────
 * The user drags a SeekBar across a rainbow strip (hue 0–360°).
 * Two shades are derived from that hue:
 *   light = HSV(hue, 0.25, 0.95)   — pale, used at the top of the list
 *   dark  = HSV(hue, 0.88, 0.28)   — rich/deep, used at the bottom
 * The live gradient preview shows how these two shades will blend across the cards.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREF_FILE        = "amp_settings"
        const val KEY_PLAYLIST_HUE = "playlist_hue"
        const val DEFAULT_HUE      = 120f   // green — matches the original hardcoded gradient
    }

    // ── Views ─────────────────────────────────────────────────────────────────

    private lateinit var vRainbowStrip:    View
    private lateinit var vGradientPreview: View
    private lateinit var vHueDot:          View
    private lateinit var sbHue:            SeekBar
    private lateinit var tvHueValue:       TextView

    // ── State ─────────────────────────────────────────────────────────────────

    private var currentHue: Float = DEFAULT_HUE

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        bindViews()

        // Load the persisted hue (falls back to green if none saved yet)
        currentHue = getSharedPreferences(PREF_FILE, MODE_PRIVATE)
            .getFloat(KEY_PLAYLIST_HUE, DEFAULT_HUE)

        setupRainbowStrip()
        setupHuePicker()

        // Back button
        findViewById<android.widget.ImageButton>(R.id.ibSettingsBack)
            .setOnClickListener { finish() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Setup
    // ─────────────────────────────────────────────────────────────────────────

    private fun bindViews() {
        vRainbowStrip    = findViewById(R.id.vRainbowStrip)
        vGradientPreview = findViewById(R.id.vGradientPreview)
        vHueDot          = findViewById(R.id.vHueDot)
        sbHue            = findViewById(R.id.sbHue)
        tvHueValue       = findViewById(R.id.tvHueValue)
    }

    /** Paints the rainbow strip that acts as a visual hue reference below the SeekBar. */
    private fun setupRainbowStrip() {
        // 7 key hues spanning the full 0–360° wheel, wrapping back to red
        val rainbowColors = intArrayOf(
            Color.HSVToColor(floatArrayOf(0f,   1f, 1f)),   // red
            Color.HSVToColor(floatArrayOf(60f,  1f, 1f)),   // yellow
            Color.HSVToColor(floatArrayOf(120f, 1f, 1f)),   // green
            Color.HSVToColor(floatArrayOf(180f, 1f, 1f)),   // cyan
            Color.HSVToColor(floatArrayOf(240f, 1f, 1f)),   // blue
            Color.HSVToColor(floatArrayOf(300f, 1f, 1f)),   // magenta
            Color.HSVToColor(floatArrayOf(360f, 1f, 1f)),   // red (closes the loop)
        )
        val gd = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, rainbowColors)
        gd.cornerRadius = dpToPx(6f)
        vRainbowStrip.background = gd
    }

    private fun setupHuePicker() {
        sbHue.max      = 360
        sbHue.progress = currentHue.toInt()

        // Render the initial state before the user touches anything
        updatePreview(currentHue)

        sbHue.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                currentHue = progress.toFloat()
                updatePreview(currentHue)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                // Persist when the user lifts their finger
                saveHue(currentHue)
            }
        })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Live preview
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Updates the gradient preview strip and the hue-dot indicator for [hue].
     * The two derived shades are identical to what [PlaylistAdapter] produces,
     * so what the user sees here is exactly what they'll get on the list.
     */
    private fun updatePreview(hue: Float) {
        val light = lightShade(hue)
        val dark  = darkShade(hue)

        // Full-width gradient strip showing the light-to-dark result
        val gd = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(light, dark)
        )
        gd.cornerRadius = dpToPx(12f)
        vGradientPreview.background = gd

        // Small circle showing the pure vivid hue the slider is pointing at
        val pure = Color.HSVToColor(floatArrayOf(hue, 0.85f, 0.80f))
        val dot  = GradientDrawable().apply {
            shape         = GradientDrawable.OVAL
            setColor(pure)
        }
        vHueDot.background = dot

        // Numeric label
        tvHueValue.text = "${hue.toInt()}°"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Persistence
    // ─────────────────────────────────────────────────────────────────────────

    private fun saveHue(hue: Float) {
        getSharedPreferences(PREF_FILE, MODE_PRIVATE)
            .edit()
            .putFloat(KEY_PLAYLIST_HUE, hue)
            .apply()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shade helpers — kept in sync with PlaylistAdapter
    // ─────────────────────────────────────────────────────────────────────────

    private fun lightShade(hue: Float): Int =
        Color.HSVToColor(floatArrayOf(hue, 0.25f, 0.95f))

    private fun darkShade(hue: Float): Int =
        Color.HSVToColor(floatArrayOf(hue, 0.88f, 0.28f))

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    private fun dpToPx(dp: Float): Float =
        dp * resources.displayMetrics.density
}
