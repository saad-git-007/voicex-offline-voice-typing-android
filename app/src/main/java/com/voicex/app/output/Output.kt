package com.voicex.app.output

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.text.InputType
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
 * Optional auto-paste. Acts ONLY on the focused editable field, and only right after a dictation the user started.
 * The only text read is that node's current text, and only in the SET_TEXT fallback, to keep what is already
 * there; it is never stored or logged.
 *
 * Three ways in, in order:
 *  1. ACTION_PASTE on the focused node: the app pastes the clipboard (which already holds the transcript) itself.
 *  2. ACTION_SET_TEXT on it: insert at the cursor, keeping the existing text.
 *  3. Typing it through the keyboard connection (Android 13+): the accessibility input method commits the text
 *     into the field exactly as a keyboard would. This reaches fields that expose neither action, such as many
 *     Jetpack Compose and web text boxes.
 */
object AutoPaster {
  private const val TAG = "VoiceX-paste"

  enum class Outcome { PASTED, SET_TEXT, TYPED, FAILED }

  /** Why the last [paste] failed, for the activity log; describes the field, never its text. */
  @Volatile var lastFailure: String = ""
    private set

  fun paste(service: AccessibilityService, text: String): Outcome {
    lastFailure = ""
    val node = focusedEditable(service)
    if (node != null) {
      if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_PASTE } && node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
        return Outcome.PASTED
      }
      // Never SET_TEXT a password field: its text is masked and would be overwritten with bullets.
      if (!node.isPassword && setText(node, text)) {
        return Outcome.SET_TEXT
      }
    }
    if (typeThroughKeyboard(service, text)) return Outcome.TYPED
    lastFailure = describe(service, node)
    Log.i(TAG, "paste failed: $lastFailure")
    return Outcome.FAILED
  }

  private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
    val existing: CharSequence = if (node.isShowingHintText) "" else node.text ?: ""
    val r = TextInsert.insert(existing, node.textSelectionStart, node.textSelectionEnd, text)
    val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, r.text) }
    if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
    val sel = Bundle().apply {
      putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, r.cursor)
      putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, r.cursor)
    }
    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
    return true
  }

  /** The input-focused node if it is editable; otherwise the focused editable node inside it or its window. */
  private fun focusedEditable(service: AccessibilityService): AccessibilityNodeInfo? {
    val f = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    if (f != null && f.isEditable) return f
    f?.let { n -> findEditable(n, requireFocus = false)?.let { return it } }
    val roots = buildList {
      service.rootInActiveWindow?.let { add(it) }
      runCatching { service.windows }.getOrNull()?.filter { it.isFocused && it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }?.mapNotNullTo(this) { it.root }
    }
    for (r in roots) findEditable(r, requireFocus = true)?.let { return it }
    return null
  }

  /** Breadth-first search for an editable node (focused, if [requireFocus]); bounded so a huge tree can't stall. */
  private fun findEditable(root: AccessibilityNodeInfo, requireFocus: Boolean): AccessibilityNodeInfo? {
    val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
    var seen = 0
    while (queue.isNotEmpty() && seen++ < 3000) {
      val n = queue.removeFirst()
      if (n.isEditable && (!requireFocus || n.isFocused)) return n
      for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
    }
    return null
  }

  private fun typeThroughKeyboard(service: AccessibilityService, text: String): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val im = service.inputMethod ?: return false
    val ic = im.currentInputConnection ?: return false
    val info = im.currentInputEditorInfo
    if (info == null || info.inputType == InputType.TYPE_NULL) return false
    ic.commitText(text, 1, null)
    return true
  }

  private fun describe(service: AccessibilityService, node: AccessibilityNodeInfo?): String {
    val field =
      if (node == null) "no focused editable field found"
      else "field ${node.className?.toString()?.substringAfterLast('.')} in ${node.packageName} offers actions ${node.actionList.joinToString(",") { it.id.toString() }}"
    val kb =
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) "keyboard typing needs Android 13"
      else service.inputMethod?.let { im -> if (im.currentInputConnection == null) "no keyboard connection" else "keyboard connection unusable" } ?: "no input method"
    return "$field; $kb"
  }
}
