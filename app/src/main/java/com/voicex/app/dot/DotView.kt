package com.voicex.app.dot

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.voicex.app.audio.RecordingCap
import com.voicex.app.dictation.DotState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The floating dot: a plain View drawn on a Canvas. Layout, left to right: [label pill] [dot] (or mirrored when
 * [labelOnRight]). The window is sized to [preferredWidth] x [preferredHeight] by the service.
 *
 * Glyphs share the logo's language: the line-art mic with its "live" dot, round caps, white on colour.
 */
@SuppressLint("ViewConstructor")
class DotView(context: Context, private val listener: Listener) : View(context) {
  interface Listener {
    fun onTap()

    fun onLongPress()

    fun onDrag(dx: Int, dy: Int)

    fun onDragEnd()
  }

  private val density = resources.displayMetrics.density

  var dotSizePx = (48 * density).toInt()
    set(v) {
      field = v
      requestLayout()
      invalidate()
    }

  var idleAlpha = 0.6f
    set(v) {
      field = v
      invalidate()
    }

  /**
   * Small language tag drawn on the idle / recording dot ("EN", "UR"); null = none. Read at every draw, so the tag
   * always shows the current language even if a settings-change event was missed.
   */
  var badgeSource: () -> String? = { null }
    set(v) {
      field = v
      invalidate()
    }

  var hapticsEnabled = true
  var labelOnRight = false

  var state: DotState = DotState.Idle
    set(v) {
      field = v
      animate = v is DotState.Recording || v is DotState.Transcribing
      if (animate) postInvalidateOnAnimation() else invalidate()
    }

  private var animate = false

  /** Pulse margin around the circle, so the recording halo isn't clipped. */
  val pad: Int
    get() = (dotSizePx * 0.14f).toInt()

  private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
  private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
  private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
  }
  private val glyphFill = Paint(Paint.ANTI_ALIAS_FLAG)
  private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 24, 22, 38) }
  private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE
    textSize = 12.5f * density
    isFakeBoldText = true
  }
  private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BRAND_A }
  private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE
    isFakeBoldText = true
    textAlign = Paint.Align.CENTER
  }
  private val path = Path()
  private val rect = RectF()

  fun label(): String? =
    when (val s = state) {
      is DotState.Recording -> RecordingCap.formatElapsed(SystemClock.elapsedRealtime() - s.startedAt)
      is DotState.Done -> s.label
      is DotState.Info -> s.label
      is DotState.Error -> s.kind.message
      else -> null
    }

  private fun labelWidth(text: String?): Int = if (text == null) 0 else (textPaint.measureText(text) + 20 * density).toInt() + (6 * density).toInt()

  /** Width of the label area for the current state ("0:00" is reserved so the window doesn't jitter). */
  private fun labelSlot(): Int =
    when (state) {
      is DotState.Recording -> labelWidth("00:00")
      else -> labelWidth(label())
    }

  fun preferredWidth(): Int = dotSizePx + 2 * pad + labelSlot()

  fun preferredHeight(): Int = dotSizePx + 2 * pad

  /** X of the circle's left edge inside this view. */
  fun dotLeftInView(): Int = if (labelOnRight) pad else labelSlot() + pad

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(preferredWidth(), preferredHeight())

  override fun onDraw(canvas: Canvas) {
    val s = dotSizePx.toFloat()
    val left = dotLeftInView().toFloat()
    val cx = left + s / 2
    val cy = pad + s / 2
    val r = s / 2
    val now = SystemClock.elapsedRealtime()
    val st = state

    // label pill
    label()?.let { text ->
      val tw = textPaint.measureText(text)
      val ph = 24 * density
      val pw = tw + 20 * density
      val px = if (labelOnRight) left + s + pad + 6 * density - pad else left - pad - pw + pad - 6 * density
      rect.set(px, cy - ph / 2, px + pw, cy + ph / 2)
      pillPaint.alpha = 230
      canvas.drawRoundRect(rect, ph / 2, ph / 2, pillPaint)
      val fm = textPaint.fontMetrics
      canvas.drawText(text, px + 10 * density, cy - (fm.ascent + fm.descent) / 2, textPaint)
    }

    var alpha = 1f
    var radius = r
    when (st) {
      DotState.Idle -> {
        alpha = idleAlpha
        fill.shader = null
        fill.color = Color.rgb(46, 47, 56)
      }
      is DotState.Recording -> {
        val elapsed = now - st.startedAt
        val warn = RecordingCap(st.capSeconds).phase(elapsed) != RecordingCap.Phase.NORMAL
        val period = if (warn) 450.0 else 1400.0
        val t = ((sin(2 * PI * (now % period.toLong()) / period) + 1) / 2).toFloat()
        radius = r * (0.92f + 0.08f * t)
        fill.shader = null
        fill.color = Color.rgb(229, 56, 59)
        halo.strokeWidth = pad * 0.9f
        halo.color = if (warn) Color.rgb(255, 176, 32) else Color.rgb(229, 56, 59)
        halo.alpha = ((if (warn) 0.85f else 0.45f) * (1 - t) * 255).toInt()
        canvas.drawCircle(cx, cy, r + pad * 0.45f * (0.5f + t), halo)
      }
      DotState.Transcribing -> {
        fill.shader = LinearGradient(left, pad.toFloat(), left + s, pad + s, BRAND_A, BRAND_B, Shader.TileMode.CLAMP)
      }
      is DotState.Done -> {
        fill.shader = null
        fill.color = Color.rgb(30, 158, 90)
      }
      is DotState.Info -> {
        fill.shader = null
        fill.color = Color.rgb(70, 72, 84)
      }
      is DotState.Error -> {
        fill.shader = null
        fill.color = Color.rgb(242, 140, 40)
      }
    }
    fill.alpha = (alpha * 255).toInt()
    canvas.drawCircle(cx, cy, radius, fill)

    glyph.strokeWidth = s * 0.05f
    glyph.color = if (st == DotState.Idle) Color.rgb(196, 199, 207) else Color.WHITE
    glyph.alpha = ((if (st == DotState.Idle) alpha.coerceAtLeast(0.75f) else 1f) * 255).toInt()
    glyphFill.color = glyph.color
    glyphFill.alpha = glyph.alpha
    when (st) {
      DotState.Transcribing -> {
        val a = (now % 900) / 900f * 360f
        rect.set(cx - s * 0.24f, cy - s * 0.24f, cx + s * 0.24f, cy + s * 0.24f)
        canvas.drawArc(rect, a, 270f, false, glyph)
      }
      is DotState.Done -> {
        path.reset()
        path.moveTo(cx - s * 0.20f, cy + s * 0.01f)
        path.lineTo(cx - s * 0.05f, cy + s * 0.15f)
        path.lineTo(cx + s * 0.21f, cy - s * 0.13f)
        canvas.drawPath(path, glyph)
      }
      is DotState.Error -> {
        canvas.drawLine(cx, cy - s * 0.20f, cx, cy + s * 0.04f, glyph)
        canvas.drawCircle(cx, cy + s * 0.18f, s * 0.045f, glyphFill)
      }
      else -> drawMic(canvas, cx, cy, s, fill.color)
    }

    // language badge, bottom-right on the circle
    val b = badgeSource()
    if (b != null && (st == DotState.Idle || st is DotState.Recording)) {
      badgeText.textSize = s * 0.2f
      val bw = badgeText.measureText(b) + s * 0.14f
      val bh = s * 0.3f
      val bx = cx + r * 0.78f - bw / 2
      val by = cy + r * 0.78f - bh / 2
      rect.set(bx, by, bx + bw, by + bh)
      badgePaint.alpha = (alpha.coerceAtLeast(0.85f) * 255).toInt()
      canvas.drawRoundRect(rect, bh / 2, bh / 2, badgePaint)
      val fm = badgeText.fontMetrics
      canvas.drawText(b, bx + bw / 2, by + bh / 2 - (fm.ascent + fm.descent) / 2, badgeText)
    }

    if (animate) postInvalidateOnAnimation()
  }

  /**
   * Mic glyph, the logo's line-art mic in miniature: outlined capsule with the small "live" dot inside, cradle,
   * stem and base, one stroke weight with round caps.
   */
  @Suppress("UNUSED_PARAMETER")
  private fun drawMic(canvas: Canvas, cx: Float, cy: Float, s: Float, holeColor: Int) {
    val w = s * 0.19f
    val top = cy - s * 0.27f
    val bottom = cy + s * 0.04f
    rect.set(cx - w / 2, top, cx + w / 2, bottom)
    canvas.drawRoundRect(rect, w / 2, w / 2, glyph)
    canvas.drawCircle(cx, top + w / 2, s * 0.034f, glyphFill)
    val cr = s * 0.18f
    val arcCy = cy - s * 0.07f
    rect.set(cx - cr, arcCy - cr, cx + cr, arcCy + cr)
    canvas.drawArc(rect, 8f, 164f, false, glyph)
    val stemTop = arcCy + cr
    val stemBottom = cy + s * 0.25f
    canvas.drawLine(cx, stemTop, cx, stemBottom, glyph)
    canvas.drawLine(cx - s * 0.065f, stemBottom, cx + s * 0.065f, stemBottom, glyph)
  }

  // ---- touch: tap, long-press, drag -------------------------------------------------------------------------
  private val slop = ViewConfiguration.get(context).scaledTouchSlop
  private var downX = 0f
  private var downY = 0f
  private var lastX = 0f
  private var lastY = 0f
  private var dragging = false
  private var longPressed = false
  private val longPress = Runnable {
    longPressed = true
    listener.onLongPress()
  }

  @SuppressLint("ClickableViewAccessibility")
  override fun onTouchEvent(e: MotionEvent): Boolean {
    when (e.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        downX = e.rawX
        downY = e.rawY
        lastX = downX
        lastY = downY
        dragging = false
        longPressed = false
        postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
      }
      MotionEvent.ACTION_MOVE -> {
        if (!dragging && !longPressed && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) {
          dragging = true
          removeCallbacks(longPress)
        }
        if (dragging) {
          listener.onDrag((e.rawX - lastX).toInt(), (e.rawY - lastY).toInt())
          lastX += (e.rawX - lastX).toInt()
          lastY += (e.rawY - lastY).toInt()
        }
      }
      MotionEvent.ACTION_UP -> {
        removeCallbacks(longPress)
        when {
          dragging -> listener.onDragEnd()
          !longPressed -> listener.onTap()
        }
      }
      MotionEvent.ACTION_CANCEL -> {
        removeCallbacks(longPress)
        if (dragging) listener.onDragEnd()
      }
    }
    return true
  }

  fun haptic(kind: Int) {
    if (hapticsEnabled) performHapticFeedback(kind)
  }

  companion object {
    val BRAND_A = Color.rgb(0x6C, 0x4C, 0xF1)
    val BRAND_B = Color.rgb(0x2F, 0x6B, 0xF0)

    const val HAPTIC_TICK = HapticFeedbackConstants.CLOCK_TICK
    const val HAPTIC_CONFIRM = HapticFeedbackConstants.CONFIRM
    const val HAPTIC_REJECT = HapticFeedbackConstants.REJECT
    const val HAPTIC_LONG = HapticFeedbackConstants.LONG_PRESS
  }
}
