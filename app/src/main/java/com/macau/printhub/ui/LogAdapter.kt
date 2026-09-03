package com.macau.printhub.ui

import android.graphics.Color
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.macau.printhub.model.LogEntry
import java.util.Date

/**
 * RecyclerView adapter for print log entries.
 * Shows: time, source, target printer, summary, success/fail, error message.
 */
class LogAdapter : ListAdapter<LogEntry, LogAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 8)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = 4
            layoutParams = lp
        }
        val tvLine1 = TextView(ctx).apply {
            textSize = 12f
        }
        val tvLine2 = TextView(ctx).apply {
            textSize = 11f
            setTextColor(0x99FFFFFF.toInt())
        }
        container.addView(tvLine1)
        container.addView(tvLine2)
        return VH(container, tvLine1, tvLine2)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = getItem(position)
        val time = DateFormat.format("MM-dd HH:mm:ss", Date(entry.timestamp))
        val status = if (entry.success) "✓" else "✗"
        val statusColor: Int = if (entry.success) Color.parseColor("#4CAF50") else Color.parseColor("#F44336")

        holder.tvLine1.text = "$status  $time  ${entry.source} → ${entry.targetPrinter}"
        holder.tvLine1.setTextColor(statusColor)

        holder.tvLine2.text = buildString {
            append(entry.summary)
            if (!entry.error.isNullOrBlank()) append("  |  錯誤：${entry.error}")
        }
    }

    class VH(
        container: View,
        val tvLine1: TextView,
        val tvLine2: TextView,
    ) : RecyclerView.ViewHolder(container)

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<LogEntry>() {
            override fun areItemsTheSame(a: LogEntry, b: LogEntry) = a.id == b.id
            override fun areContentsTheSame(a: LogEntry, b: LogEntry) = a == b
        }
    }
}
