package com.voicex.app.output

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

object ClipboardWriter {
  /** Puts [text] on the clipboard, explicitly marked NOT sensitive (so the clipboard preview may show it). */
  fun copy(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText("VoiceX", text)
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) }
    cm.setPrimaryClip(clip)
  }
}

/** Pure text insertion used by the ACTION_SET_TEXT fallback (unit-tested in TextInsertTest). */
object TextInsert {
  data class Result(val text: String, val cursor: Int)

  /**
   * Replaces the selection [selStart, selEnd) of [existing] with [insert], or appends at the end when there is no
   * valid selection. Returns the new text and the cursor position after the inserted text.
   */
  fun insert(existing: CharSequence, selStart: Int, selEnd: Int, insert: String): Result {
    val len = existing.length
    var a = minOf(selStart, selEnd)
    var b = maxOf(selStart, selEnd)
    if (a < 0 || b < 0 || a > len) {
      a = len
      b = len
    }
    b = b.coerceAtMost(len)
    val out = StringBuilder(len + insert.length).append(existing, 0, a).append(insert).append(existing, b, len)
    return Result(out.toString(), a + insert.length)
  }
}

/**
 * Optional auto-paste. Acts ONLY on the focused editable node, and only right after a dictation the user started.
 * The only text read is that node's current text, and only in the SET_TEXT fallback, to keep what is already
 * there; it is never stored or logged.
 */
object AutoPaster {
  private const val TAG = "VoiceX-paste"

  enum class Outcome { PASTED, SET_TEXT, FAILED }

  fun paste(service: AccessibilityService, text: String): Outcome {
    val node = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    if (node == null || !node.isEditable) {
      Log.i(TAG, "no focused editable field")
      return Outcome.FAILED
    }
    // 1) ACTION_PASTE: the app pastes the clipboard (which already holds the transcript) itself.
    //    performAction is synchronous with the target app, so true means it handled the paste.
    if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_PASTE } && node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
      return Outcome.PASTED
    }
    // 2) ACTION_SET_TEXT: keep the existing text, insert at the cursor. Never on password fields, whose text is
    //    masked and would be overwritten with bullets.
    if (node.isPassword) return Outcome.FAILED
    val existing: CharSequence = if (node.isShowingHintText) "" else node.text ?: ""
    val r = TextInsert.insert(existing, node.textSelectionStart, node.textSelectionEnd, text)
    val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, r.text) }
    if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return Outcome.FAILED
    val sel = Bundle().apply {
      putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, r.cursor)
      putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, r.cursor)
    }
    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
    return Outcome.SET_TEXT
  }
}
