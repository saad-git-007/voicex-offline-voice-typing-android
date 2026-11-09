package com.voicex.app.engine

import java.util.Locale

/** Word-level comparison used by the benchmark's correctness check (unit-tested in WordDiffTest). */
object WordDiff {
  /** Lower-case words with punctuation stripped ("Americans," -> "americans"). Apostrophes are kept. */
  fun words(text: String): List<String> =
    text.lowercase(Locale.ROOT)
      .replace(Regex("[^\\p{L}\\p{N}' ]+"), " ")
      .split(' ')
      .map { it.trim('\'') }
      .filter { it.isNotEmpty() }

  /** Levenshtein distance over words (substitution, insertion, deletion each cost 1). */
  fun distance(a: String, b: String): Int {
    val x = words(a)
    val y = words(b)
    var prev = IntArray(y.size + 1) { it }
    var cur = IntArray(y.size + 1)
    for (i in 1..x.size) {
      cur[0] = i
      for (j in 1..y.size) {
        val sub = if (x[i - 1] == y[j - 1]) 0 else 1
        cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + sub)
      }
      val t = prev
      prev = cur
      cur = t
    }
    return prev[y.size]
  }

  /** The benchmark rule: an exact match or at most [maxWords] word differing from the CPU reference. */
  fun matches(reference: String, candidate: String, maxWords: Int = 1): Boolean =
    words(candidate).isNotEmpty() && distance(reference, candidate) <= maxWords
}
