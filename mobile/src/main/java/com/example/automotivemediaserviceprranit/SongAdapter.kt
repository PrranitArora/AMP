package com.example.automotivemediaserviceprranit

import android.support.v4.media.MediaBrowserCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Displays a flat list of playable songs (title only — no artist subtitle).
 */
class SongAdapter(
    private val onSongClick: (MediaBrowserCompat.MediaItem) -> Unit
) : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

    private var items: List<MediaBrowserCompat.MediaItem> = emptyList()

    fun updateSongs(newItems: List<MediaBrowserCompat.MediaItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    inner class SongViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvSongTitle)

        init {
            view.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onSongClick(items[pos])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_song, parent, false)
        return SongViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.tvTitle.text = items[position].description.title ?: "Unknown"
    }

    override fun getItemCount(): Int = items.size
}
