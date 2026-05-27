package com.example.automotivemediaserviceprranit

import android.content.res.ColorStateList
import android.graphics.Color
import android.support.v4.media.MediaBrowserCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

/**
 * Displays playlist rows, each tinted with a unique shade of a user-chosen
 * colour that transitions from **light** (top, position 0) to **dark**
 * (bottom, last position).
 *
 * The base hue is driven by [baseHue] (0–360°, default 120° = green to
 * match the original design).  Update [baseHue] and call
 * [notifyDataSetChanged] to re-colour the list after the user changes the
 * setting in SettingsActivity.
 *
 * Text and icon colours automatically switch between black (on light cards)
 * and white (on dark cards) based on the background luminance.
 */
class PlaylistAdapter(
    private val onPlaylistClick: (MediaBrowserCompat.MediaItem) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.PlaylistViewHolder>() {

    /**
     * Hue (0–360°) used to derive the light and dark gradient stops.
     * Set this from [MainActivity] after loading the value from SharedPreferences.
     */
    var baseHue: Float = 120f   // default green

    private var items: List<MediaBrowserCompat.MediaItem> = emptyList()

    fun updatePlaylists(newItems: List<MediaBrowserCompat.MediaItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    inner class PlaylistViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card:         MaterialCardView = view as MaterialCardView
        val tvName:       TextView  = view.findViewById(R.id.tvPlaylistName)
        val tvFolderPath: TextView  = view.findViewById(R.id.tvFolderPath)
        val ivIcon:       ImageView = view.findViewById(R.id.ivPlaylistIcon)
        val ivChevron:    ImageView = view.findViewById(R.id.ivChevron)

        init {
            view.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onPlaylistClick(items[pos])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist, parent, false)
        return PlaylistViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        val item = items[position]

        holder.tvName.text       = item.description.title    ?: "Unknown"
        holder.tvFolderPath.text = item.description.subtitle ?: ""

        // ── User-chosen hue gradient ─────────────────────────────────────
        val bgColor   = colorShade(position, items.size)
        val textColor = if (isLightColor(bgColor)) Color.BLACK else Color.WHITE
        val dimColor  = withAlpha(textColor, 0.60f)
        val faintColor= withAlpha(textColor, 0.30f)

        holder.card.setCardBackgroundColor(bgColor)
        // Ripple visible on any background
        holder.card.rippleColor = ColorStateList.valueOf(withAlpha(textColor, 0.18f))

        holder.tvName.setTextColor(textColor)
        holder.tvFolderPath.setTextColor(dimColor)
        holder.ivIcon.imageTintList    = ColorStateList.valueOf(dimColor)
        holder.ivChevron.imageTintList = ColorStateList.valueOf(faintColor)
    }

    override fun getItemCount(): Int = items.size

    // ── Colour helpers ────────────────────────────────────────────────────

    /**
     * Linearly interpolates from a light tint of [baseHue] at the top to a
     * deep/rich shade of [baseHue] at the bottom.
     *
     * The two derived stops mirror what [SettingsActivity] shows in its live
     * preview so the user always sees exactly what they'll get.
     */
    private fun colorShade(position: Int, total: Int): Int {
        val light = Color.HSVToColor(floatArrayOf(baseHue, 0.25f, 0.95f))
        val dark  = Color.HSVToColor(floatArrayOf(baseHue, 0.88f, 0.28f))

        if (total <= 1) return light

        val t = position.toFloat() / (total - 1).toFloat()

        return Color.rgb(
            lerp(Color.red(light),   Color.red(dark),   t),
            lerp(Color.green(light), Color.green(dark), t),
            lerp(Color.blue(light),  Color.blue(dark),  t)
        )
    }

    /** True when the colour is light enough to warrant black text. */
    private fun isLightColor(color: Int): Boolean {
        val r = Color.red(color)   / 255.0
        val g = Color.green(color) / 255.0
        val b = Color.blue(color)  / 255.0
        // Relative luminance (WCAG formula, simplified)
        val lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
        return lum > 0.40
    }

    private fun lerp(a: Int, b: Int, t: Float): Int =
        (a + (b - a) * t + 0.5f).toInt().coerceIn(0, 255)

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb(
            (alpha * 255 + 0.5f).toInt().coerceIn(0, 255),
            Color.red(color), Color.green(color), Color.blue(color)
        )
}
