package com.voicex.app.dot

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.voicex.app.model.Lang

/**
 * The dot's long-press menu: English / اردو / Hide. A small overlay window of its own, shown next to the dot and
 * closed by a choice, a touch anywhere else, or the keyboard closing.
 */
class DotMenu(
  private val context: Context,
  private val wm: WindowManager,
  private val onLanguage: (Lang) -> Unit,
  private val onHide: () -> Unit,
) {
  private var view: View? = null

  val isShowing: Boolean
    get() = view != null

  private val density = context.resources.displayMetrics.density

  private fun dp(v: Float) = (v * density).toInt()

  /**
   * Shows the menu next to the dot whose circle is at screen ([dotX], [dotY]) with size [dotSize].
   * [windowType] matches the dot's window (accessibility overlay or app overlay).
   */
  @SuppressLint("ClickableViewAccessibility")
  fun show(current: Lang, dotX: Int, dotY: Int, dotSize: Int, windowType: Int) {
    dismiss()
    val list =
      LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        background = GradientDrawable().apply {
          cornerRadius = dp(16f).toFloat()
          setColor(Color.argb(245, 30, 28, 46))
          setStroke(dp(1f), Color.argb(70, 255, 255, 255))
        }
        setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
        elevation = dp(8f).toFloat()
      }
    Lang.entries.forEach { l ->
      val label = if (l == Lang.EN) "English" else "${l.native}  Urdu"
      list.addView(item(label, selected = l == current) { dismiss(); onLanguage(l) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
    list.addView(View(context).apply { setBackgroundColor(Color.argb(50, 255, 255, 255)) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f)).apply { setMargins(dp(8f), dp(4f), dp(8f), dp(4f)) })
    list.addView(item("Hide until next keyboard", selected = false, muted = true) { dismiss(); onHide() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    list.setOnTouchListener { _, e ->
      if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) dismiss()
      false
    }

    list.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
    val w = list.measuredWidth
    val h = list.measuredHeight
    val screen = wm.currentWindowMetrics.bounds
    val gap = dp(8f)
    // above the dot when there is room (the keyboard is below it), otherwise below
    val y = if (dotY - gap - h >= dp(24f)) dotY - gap - h else dotY + dotSize + gap
    val x = (dotX + dotSize / 2 - w / 2).coerceIn(dp(8f), (screen.width() - w - dp(8f)).coerceAtLeast(dp(8f)))
    val p =
      WindowManager.LayoutParams(
        w,
        h,
        windowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
          WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
      ).apply {
        gravity = Gravity.TOP or Gravity.START
        this.x = x
        this.y = y
        title = "VoiceX dot menu"
      }
    runCatching { wm.addView(list, p) }.onSuccess { view = list }
  }

  private fun item(text: String, selected: Boolean, muted: Boolean = false, onClick: () -> Unit): View =
    TextView(context).apply {
      this.text = if (selected) "✓  $text" else "     $text"
      // the Urdu label must not flip the row to right-to-left: every row reads (and aligns) left to right
      textDirection = View.TEXT_DIRECTION_LTR
      textAlignment = View.TEXT_ALIGNMENT_VIEW_START
      layoutDirection = View.LAYOUT_DIRECTION_LTR
      textSize = if (muted) 14f else 16f
      setTextColor(if (muted) Color.argb(200, 200, 202, 214) else Color.WHITE)
      typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
      minWidth = dp(190f)
      setPadding(dp(14f), dp(11f), dp(18f), dp(11f))
      background = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), null, GradientDrawable().apply { cornerRadius = dp(10f).toFloat(); setColor(Color.WHITE) })
      if (selected) {
        background = RippleDrawable(
          ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
          GradientDrawable().apply { cornerRadius = dp(10f).toFloat(); setColor(Color.argb(70, 0x6C, 0x4C, 0xF1)) },
          null,
        )
      }
      isClickable = true
      setOnClickListener { onClick() }
    }

  fun dismiss() {
    val v = view ?: return
    view = null
    runCatching { wm.removeViewImmediate(v) }
  }
}
