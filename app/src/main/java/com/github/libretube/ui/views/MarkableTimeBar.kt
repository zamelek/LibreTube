package com.github.libretube.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.view.marginLeft
import androidx.media3.common.util.UnstableApi
import com.github.libretube.api.obj.Segment
import com.github.libretube.extensions.dpToPx
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.ThemeHelper
import com.google.android.material.R

/**
 * TimeBar that can be marked with SponsorBlock Segments
 */
@UnstableApi
open class MarkableTimeBar(
    context: Context,
    attributeSet: AttributeSet? = null
) : DismissableTimeBar(context, attributeSet) {
    private var segments = listOf<Segment>()
    private var length: Int = 0

    // slightly thicker than the progress line, so that the segments are visible without being loud
    private val segmentHeight = 3f.dpToPx()

    override fun onDraw(canvas: Canvas) {
        // draw the segments below the progress line and the scrubber, so that they don't cover them
        drawSegments(canvas)
        super.onDraw(canvas)
    }

    private fun drawSegments(canvas: Canvas) {
        if (exoPlayer == null) return

        canvas.save()
        val horizontalOffset = (parent as View).marginLeft
        length = canvas.width - horizontalOffset * 2
        val marginY = (canvas.height - segmentHeight) / 2
        val themeColor = ThemeHelper.getThemeColor(context, R.attr.colorOnSecondary)
        val useCustomColors = PreferenceHelper.getBoolean("sb_enable_custom_colors", false)
        val paint = Paint()

        segments.forEach {
            val (start, end) = it.segmentStartAndEnd
            // skip labels for the whole video and point in time markers (e.g. highlights)
            if (it.actionType == Segment.TYPE_FULL || end <= start) return@forEach

            // every category has its own color, the user can change it if custom colors are enabled
            val defaultColor = DEFAULT_CATEGORY_COLORS[it.category] ?: themeColor
            val color = if (useCustomColors) {
                PreferenceHelper.getInt(it.category + "_color", defaultColor)
            } else {
                defaultColor
            }
            // a bit transparent, so that the colors blend into the dark player design
            paint.color = ColorUtils.setAlphaComponent(color, SEGMENT_ALPHA)

            canvas.drawRect(
                Rect(
                    start.toLength() + horizontalOffset,
                    marginY,
                    end.toLength() + horizontalOffset,
                    marginY + segmentHeight
                ),
                paint
            )
        }
        canvas.restore()
    }

    private fun Float.toLength(): Int {
        return (this * 1000 / exoPlayer!!.duration * length).toInt()
    }

    fun setSegments(segments: List<Segment>) {
        this.segments = segments
    }

    fun clearSegments() {
        segments = listOf()
    }

    companion object {
        private const val SEGMENT_ALPHA = 170

        // keep in sync with the default values of the color preferences in sponsorblock_settings.xml
        private val DEFAULT_CATEGORY_COLORS = mapOf(
            "sponsor" to 0xFF00D400.toInt(),
            "selfpromo" to 0xFFFFFF00.toInt(),
            "interaction" to 0xFFCC00FF.toInt(),
            "intro" to 0xFF00FFFF.toInt(),
            "outro" to 0xFF0202ED.toInt(),
            "filler" to 0xFF7300FF.toInt(),
            "music_offtopic" to 0xFFFF9900.toInt(),
            "preview" to 0xFF008FD6.toInt(),
            "exclusive_access" to 0xFF008A5C.toInt(),
            "hook" to 0xFF395699.toInt(),
        )
    }
}
