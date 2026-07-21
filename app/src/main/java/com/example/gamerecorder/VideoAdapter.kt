package com.example.gamerecorder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoAdapter(
    private var videos: List<VideoItem>,
    private val onItemClick: (VideoItem) -> Unit,
    private val onDeleteClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {

    class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tvVideoName)
        val tvInfo: TextView = itemView.findViewById(R.id.tvVideoInfo)
        val tvDate: TextView = itemView.findViewById(R.id.tvVideoDate)
        val btnPlay: ImageButton = itemView.findViewById(R.id.btnPlayVideo)
        val btnDelete: ImageButton = itemView.findViewById(R.id.btnDeleteVideo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val video = videos[position]
        holder.tvName.text = video.name

        val durationSec = video.durationMs / 1000
        val mins = durationSec / 60
        val secs = durationSec % 60
        val sizeMb = String.format(Locale.US, "%.1f MB", video.sizeBytes / (1024.0 * 1024.0))
        holder.tvInfo.text = String.format(Locale.US, "%02d:%02d • %s", mins, secs, sizeMb)

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        holder.tvDate.text = dateFormat.format(Date(video.dateAddedSec * 1000))

        holder.itemView.setOnClickListener { onItemClick(video) }
        holder.btnPlay.setOnClickListener { onItemClick(video) }
        holder.btnDelete.setOnClickListener { onDeleteClick(video) }
    }

    override fun getItemCount(): Int = videos.size

    fun updateVideos(newVideos: List<VideoItem>) {
        videos = newVideos
        notifyDataSetChanged()
    }
}
