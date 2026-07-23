package com.agent.voiceassistant.ui

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.StringRes
import androidx.recyclerview.widget.RecyclerView

data class MainPage(
    @StringRes val titleRes: Int,
    val content: View,
)

class MainPageAdapter(
    val pages: List<MainPage>,
) : RecyclerView.Adapter<MainPageAdapter.PageViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val container = FrameLayout(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        return PageViewHolder(container)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val content = pages[position].content
        (content.parent as? ViewGroup)?.removeView(content)
        holder.container.removeAllViews()
        holder.container.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    override fun getItemCount(): Int = pages.size

    class PageViewHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)
}
