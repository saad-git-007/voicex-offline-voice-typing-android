package com.voicex.app.dot

/**
 * Where the dot goes (unit-tested in DotPlacementTest). Positions are the dot circle's top-left in screen pixels.
 *
 * The saved offset is (x, dy): x from the screen's left edge, dy = keyboard top - dot top (positive = above the
 * keyboard). It is kept per orientation, so the dot follows the keyboard when its height changes.
 */
object DotPlacement {
  data class Pos(val x: Int, val y: Int)

  /** Default: right edge, just above the keyboard. */
  fun defaultOffset(screenW: Int, sizePx: Int, marginPx: Int): Pair<Int, Int> = (screenW - sizePx - marginPx) to (sizePx + marginPx)

  fun place(kbTop: Int, screenW: Int, screenH: Int, sizePx: Int, marginPx: Int, saved: Pair<Int, Int>?, topInset: Int = 0): Pos {
    val (x, dy) = saved ?: defaultOffset(screenW, sizePx, marginPx)
    return clamp(x, kbTop - dy, screenW, screenH, sizePx, topInset)
  }

  fun clamp(x: Int, y: Int, screenW: Int, screenH: Int, sizePx: Int, topInset: Int = 0): Pos =
    Pos(x.coerceIn(0, (screenW - sizePx).coerceAtLeast(0)), y.coerceIn(topInset.coerceAtMost(screenH - sizePx), (screenH - sizePx).coerceAtLeast(0)))

  /** The offset to save after a drag that left the dot at ([x], [y]). */
  fun offsetFor(x: Int, y: Int, kbTop: Int): Pair<Int, Int> = x to (kbTop - y)
}
