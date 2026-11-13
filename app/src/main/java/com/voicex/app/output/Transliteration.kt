package com.voicex.app.output

import java.text.Normalizer

/**
 * Optional "Transliteration feature": rewrites known terms in a transcript into their official transliteration
 * with full diacritics ("hazrat khalifatul masih" -> "Ḥaḍrat Khalīfatul Masīḥ").
 *
 * Speech models spell these words however they hear them, so matching is done on a loose phonetic key, not on the
 * text itself: case, accents, apostrophes, hyphens and spaces are ignored, doubled letters collapse, and common
 * spelling swaps are folded together (ee/i, oo/u, e/i, o/u, q/k, v/w, y/i, dh/z, th/s, ḍ as d or z). Phrases are
 * matched longest first, and a run of words never crosses punctuation. Pure Kotlin, unit-tested in
 * TransliterationTest.
 */
object Transliteration {
  /** Terms from the official list (Rules and Regulations of Taḥrīk-i-Jadīd, revision 2020), as they should appear. */
  val TERMS: List<String> =
    listOf(
      // A
      "Ābādī", "Āb Rasānī", "Ahmad", "Ahmadī", "Ahmadiyya", "Ahmadiyyat", "A‘lā", "Al Faḍl", "‘Ām", "Āmad", "Amānat",
      "‘Āmila", "Amīn", "Amīr", "Anjuman", "Anṣārullāh", "Aṭfālul Aḥmadiyya", "Awwal", "‘Azm",
      // B
      "Bahishtī Maqbara", "Bai‘at", "Bairūn", "Bāzār", "Buyūtudh Dhikr", "Buyūtul Ḥamd",
      // C
      "Chahārum", "Chanda",
      // D
      "Dafātir", "Daftar", "Dā‘iyān Ilallāh", "Dā‘ī Ilallāh", "Dars", "Dāruḍ", "Dārul Iftā’", "Da‘wat Ilallāh",
      "Derveshān", "Dīwān", "Ḍiyāfat", "Duvum",
      // F
      "Faḍl ‘Umar", "Fatāwā", "Fatwā", "Faurī Imdād", "Fiqha", "Fiṭrāna",
      // G
      "Ghulām", "Gosht",
      // H
      "Ḥadīth", "Ḥaḍrat", "Ḥāḍrī Nigrānī", "Ḥāfiẓ", "Ḥamd", "Ḥifẓ", "Ḥilful Fuḍūl", "Ḥiṣṣa Āmad", "Ḥiṣṣa Jā‘idād",
      "Ḥuḍūr",
      // I
      "‘Īd", "‘Īdain", "Iftā’", "Ijrā’ Parchī", "Ijtimā‘", "Imām", "Imāmuṣ Ṣalāt", "Imārat", "Intikhāb", "Irshād",
      "Ishā‘at", "Iṣlāḥ-o-Irshād", "Islām", "Istiqbāl",
      // J
      "Jā‘idād", "Jalsa Gāh", "Jalsa Sālāna", "Jamā‘at", "Jāmi‘a", "Jāmi‘āt", "Jumu‘a",
      // K
      "Khalīfa", "Khalīfatul Masīḥ", "Khalīfatul Masīḥil Awwal", "Khalīfatul Masīḥir Rābi‘", "Khalīfatul Masīḥith Thālith",
      "Khalīfatul Masīḥith Thānī", "Khārijiyya", "Khazāna", "Khidmat Khalq", "Khilāfat", "Khurāk", "Khuddāmul Ahmadiyya",
      "Khulafā’", "Khuṭbā",
      // L
      "Lajna Imāillāh", "Langar Khāna",
      // M
      "Madrassa", "Mahdī", "Maḥmūd Majālis", "Majlis", "Majlis Kārpardāz", "Majlis Mushāwarat", "Majlis Shūrā", "Makān",
      "Makānāt", "Makhzan", "Māl", "Ma‘lūmāt", "Maqbara", "Markaz", "Maṣāliḥ Qabristān", "Masīḥ Mau‘ūd", "Mehmān Nawāzī",
      "Meḥnat", "Mirzā", "Mu‘āina", "Mu‘allim", "Mu‘allimīn", "Mu‘āwin", "Mu‘āwinīn", "Mubāhala", "Muballigh",
      "Muballighīn", "Muftī", "Muḥammad", "Muḥāsaba", "Muḥāsib", "Muḥaṣṣil", "Mujāhidīn", "Muqāmī", "Murabbī",
      "Murāfa‘a Thāniyah", "Murāfa‘a Ūlā", "Mushīr", "Mūṣī", "Mūṣiya", "Mūṣiyān", "Mu‘tamadīn", "Muttaqī", "Mau‘ūd",
      // N
      "Nā’ib", "Naql-o-Ḥaml", "Nāṣir", "Nau Mubāyi‘īn", "Naẓārat", "Nāẓim", "Nāẓir", "Niẓām", "Niẓāmat", "Nuṣrat Jahān",
      // P
      "Parālī", "Pukhta Sāmān",
      // Q
      "Qaḍā’", "Qāḍī", "Qādiān", "Qā‘id", "Qānūnī", "Qur’ān",
      // R
      "Rābi‘", "Rabwah", "Rishta Nāṭa", "Roshnī",
      // S
      "Ṣad Sāla", "Ṣadaqa", "Ṣadr", "Ṣafā’ī", "Ṣaḥāba", "Ṣaḥābī", "Ṣāḥib Niṣāb", "Salām", "Ṣalāt", "Sam‘ī wa Baṣarī",
      "Ṣan‘at", "Ṣan‘at-o-Tijārat", "Shahādatul Ajānib", "Shāhid", "Sharī‘a", "Shumāriyāt", "Shūrā", "Sivum",
      // T
      "Tablīgh", "Tabshīr", "Ṭāhir", "Taḥrīk-i-Jadīd", "Tajnīd", "Ta‘līm", "Ta‘līmul Qur’ān", "Ta‘mīl-o-Tanfīdh", "Ta‘mīr",
      "Tanqīḥ Ḥisābāt", "Tarbiyat", "Tārīkh", "Taṣāwīr", "Taṣdīq Parchī", "Taṣnīf", "Tausī‘ Makān", "Thālith", "Thānī",
      "Ṭibbī Imdād", "Tijārat",
      // U
      "‘Ulyā", "‘Umūmī", "Umūr ‘Āmma", "Umūr Khārijiyya",
      // W
      "Wakālat", "Wakīl", "Wakīl A‘lā", "Wakīl Ṣan‘at-o-Tijārat", "Wakīl Ta‘mīl-o-Tanfīdh", "Wakīl Waqf Nau", "Wakīlud Dīwān",
      "Wakīlul Ishā‘at", "Wakīlul Māl", "Wakīlut Ta‘līm", "Wakīlut Tabshīr", "Wakīlut Taṣnīf", "Wakīluz Zirā‘at",
      "Waqār ‘Amal", "Waqf ‘Āriḍī", "Waqf Jadīd", "Waqf Nau", "Wāqif", "Wāqif ‘Āriḍ", "Wāqif Zindagī", "Waqifāt Nau",
      "Wāqifīn Nau", "Waṣāyā", "Waṣiyyat",
      // Z
      "Za‘īm", "Zakāt", "Zirā‘at", "Zūd Navīsān", "Ẓurūf Gilī",
    )

  /** Forms that appear only inside listed phrases ("Maḥmūd Majālis", "Ṣāḥib Niṣāb") but are often said alone. */
  val DERIVED_TERMS: List<String> = listOf("Assalām ‘Alaikum", "Wa'laikum Salām", "Khuddām", "Majālis", "Ṣāḥib")

  /** Common spellings the loose key still misses: spoken/Urdu-style spelling -> official term. */
  private val EXTRA_SPELLINGS: Map<String, String> =
    mapOf(
      "Assalamu Alaikum" to "Assalām ‘Alaikum",
      "Salam Alaikum" to "Assalām ‘Alaikum",
      "Salamu Alaikum" to "Assalām ‘Alaikum",
      "Wa Alaikum Assalam" to "Wa'laikum Salām",
      "Walaikum Assalam" to "Wa'laikum Salām",
      "Walaikumus Salam" to "Wa'laikum Salām",
      "Hazoor" to "Ḥuḍūr",
      "Huzur" to "Ḥuḍūr",
      "Tehrik Jadid" to "Taḥrīk-i-Jadīd",
      "Tehreek Jadeed" to "Taḥrīk-i-Jadīd",
      "Behishti Maqbara" to "Bahishtī Maqbara",
      "Sadar" to "Ṣadr",
      "Sadhar" to "Ṣadr",
      "Salah" to "Ṣalāt",
      "Zakah" to "Zakāt",
    ).filterValues { it in TERMS || it in DERIVED_TERMS }

  /**
   * A single word whose key equals a term's but which is ordinary English ("am", "mall", "id"): never replaced on
   * its own. It can still be part of a longer phrase ("Wakilul Mal").
   */
  private val ENGLISH_WORDS: Set<String> =
    setOf(
      // From checking every word of an English dictionary against the term keys.
      "ala", "allah", "am", "amen", "darth", "dhaka", "divan", "ed", "eyed", "hades", "id", "ids", "ied", "mall", "malls",
      "maud", "moose", "mosey", "mossy", "mouse", "mousey", "mousse", "mousy", "mouthe", "muse", "mussy", "mushier",
      "nasser", "rabbi", "sales", "sallies", "sane", "tamer", "thales", "yd",
    )

  private const val MAX_WINDOW = 8

  private class Entry(val term: String, val words: Int)

  private val index: Map<String, Entry> by lazy {
    val m = HashMap<String, Entry>()
    fun put(key: String, term: String) {
      if (key.isEmpty()) return
      val e = Entry(term, term.split(' ', '-').count { it.isNotBlank() })
      // Prefer the longer term on a key clash (more specific).
      val old = m[key]
      if (old == null || e.term.length > old.term.length) m[key] = e
    }
    for (t in TERMS + DERIVED_TERMS) for (k in termKeys(t)) put(k, t)
    for ((spelling, t) in EXTRA_SPELLINGS) put(phraseKey(spelling.split(' ')), t)
    m
  }

  /** Replaces every known term in [text]; everything else is left exactly as it was. */
  fun apply(text: String): String {
    if (text.isBlank()) return text
    val tokens = tokenize(text)
    val out = StringBuilder(text.length + 16)
    var i = 0
    while (i < tokens.size) {
      val t = tokens[i]
      if (!t.isWord) {
        out.append(t.text)
        i++
        continue
      }
      val m = longestMatch(tokens, i)
      if (m == null) {
        out.append(t.text)
        i++
        continue
      }
      out.append(m.term).append(m.suffix)
      i = m.endExclusive
    }
    return out.toString()
  }

  // ---- matching -----------------------------------------------------------------------------------------------

  private class Token(val text: String, val isWord: Boolean)

  private class Match(val term: String, val suffix: String, val endExclusive: Int)

  private val WORD = Regex("""[\p{L}\p{M}‘’'ʿʾ`]+""")

  private fun tokenize(s: String): List<Token> {
    val out = ArrayList<Token>()
    var last = 0
    for (m in WORD.findAll(s)) {
      if (m.range.first > last) out += Token(s.substring(last, m.range.first), false)
      out += Token(m.value, true)
      last = m.range.last + 1
    }
    if (last < s.length) out += Token(s.substring(last), false)
    return out
  }

  /** Word tokens from [start] that form a run separated only by spaces or hyphens (no punctuation in between). */
  private fun run(tokens: List<Token>, start: Int): List<Int> {
    val idx = arrayListOf(start)
    var j = start + 1
    while (idx.size < MAX_WINDOW && j + 1 < tokens.size) {
      val sep = tokens[j].text
      if (tokens[j].isWord || !sep.all { it == ' ' || it == '-' || it == ' ' } || !tokens[j + 1].isWord) break
      idx += j + 1
      j += 2
    }
    return idx
  }

  private fun longestMatch(tokens: List<Token>, start: Int): Match? = longestMatch(tokens, start, fuzzy = false) ?: longestMatch(tokens, start, fuzzy = true)

  /**
   * Exact keys are tried at every length before any near miss, so a loose match never swallows a neighbour that
   * an exact one would have left alone ("Wakilul Mal III" stays "Wakīlul Māl III").
   */
  private fun longestMatch(tokens: List<Token>, start: Int, fuzzy: Boolean): Match? {
    val words = run(tokens, start)
    for (n in words.size downTo 1) {
      // Near misses ("Khudna Mul Ahmadiyya") for phrases only: for a lone word a letter or two is the gap between
      // ordinary English and a term ("Muslim" / "Mu‘allim", "Shared" / "Sharī‘a").
      if (fuzzy && n == 1) continue
      val w = words.subList(0, n).map { tokens[it].text }
      // A trailing possessive ("Masih's") stays after the term.
      val lastWord = w.last()
      val poss = POSSESSIVE.find(lastWord)
      val core = if (poss != null) w.dropLast(1) + lastWord.substring(0, poss.range.first) else w
      val suffix = poss?.value ?: ""
      if (core.any { x -> x.isEmpty() || x.none { it.isLetter() } }) continue
      // Izafat / "o" connectors don't count at either end of a match.
      if (core.first().lowercase() in CONNECTORS || core.last().lowercase() in CONNECTORS) continue
      if (n == 1 && core[0].lowercase() in ENGLISH_WORDS) continue
      for (k in inputKeys(core)) {
        val e = if (fuzzy) fuzzy(k) else index[k]
        if (e != null) return Match(e.term, suffix, words[n - 1] + 1)
      }
    }
    return null
  }

  /** Letters a key may differ by from a term's key and still match: none below 10 ("the area" / "Sharia"), 1, then 2 from 14. */
  internal fun allowedEdits(keyLength: Int): Int = when {
    keyLength < 10 -> 0
    keyLength < 14 -> 1
    else -> 2
  }

  /** The closest term key within [allowedEdits]; same first letter required, ties go to the longer term. */
  private fun fuzzy(k: String): Entry? {
    val max = allowedEdits(k.length)
    if (max == 0 || k.isEmpty()) return null
    var best: Entry? = null
    var bestD = max + 1
    for ((tk, e) in index) {
      if (tk[0] != k[0] || kotlin.math.abs(tk.length - k.length) > max || allowedEdits(tk.length) == 0) continue
      val d = editDistance(k, tk, max)
      if (d < bestD || (d == bestD && best != null && e.term.length > best.term.length)) {
        best = e
        bestD = d
      }
    }
    return if (bestD <= max) best else null
  }

  /** Levenshtein distance, giving up (returning max + 1) once it must exceed [max]. */
  internal fun editDistance(a: String, b: String, max: Int): Int {
    var prev = IntArray(b.length + 1) { it }
    var cur = IntArray(b.length + 1)
    for (i in 1..a.length) {
      cur[0] = i
      var rowMin = cur[0]
      for (j in 1..b.length) {
        cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        rowMin = minOf(rowMin, cur[j])
      }
      if (rowMin > max) return max + 1
      val t = prev
      prev = cur
      cur = t
    }
    return prev[b.length]
  }

  private val POSSESSIVE = Regex("""['’]s$""")
  private val CONNECTORS = setOf("e", "i", "o")

  // ---- keys ---------------------------------------------------------------------------------------------------

  /** Keys the input words may produce: as spoken, without izafat/"o" connectors, and with a trailing izafat "e". */
  private fun inputKeys(words: List<String>): Set<String> {
    val keys = LinkedHashSet<String>()
    keys += phraseKey(words)
    if (words.size > 1) {
      val noConn = words.filterIndexed { i, w -> i == 0 || i == words.lastIndex || w.lowercase() !in CONNECTORS }
      keys += phraseKey(noConn)
      // "Waqfe Nau", "Majlise Shura": an izafat e glued to the end of a word.
      keys += phraseKey(noConn.mapIndexed { i, w -> if (i < noConn.lastIndex && w.length > 3 && w.endsWith("e", true)) w.dropLast(1) else w })
    }
    return keys
  }

  /** Keys a term is reachable by: ḍ read as d or z, assimilated article written as "ul", connectors dropped. */
  /** Every key the index holds, with its term (for tests). */
  internal fun indexForTest(): Map<String, String> = index.mapValues { it.value.term }

  internal fun termKeys(term: String): Set<String> {
    val forms = LinkedHashSet<String>()
    for (d in listOf("d", "z")) {
      val base = term.replace('ḍ', d[0]).replace('Ḍ', d[0].uppercaseChar())
      forms += base
      // Sun-letter assimilation: "Wakīlut Tabshīr" is also said/written "Wakil ul Tabshir".
      forms += ASSIMILATED.replace(base) { m -> m.groupValues[1] + "l " + m.groupValues[3] }
      // "Masīḥil Awwal" / "Masīḥ ul Awwal": the joining vowel varies.
      forms += JOINING_IL.replace(base) { m -> "ul " + m.groupValues[1] }
    }
    // The guttural kh is often heard as a plain h: "Khuddām" comes out as "Huddam".
    for (f in forms.toList()) forms += INITIAL_KH.replace(f, "H")
    val keys = LinkedHashSet<String>()
    for (f in forms) {
      val words = f.split(' ', '-').filter { it.isNotBlank() }
      keys += phraseKey(words)
      keys += phraseKey(words.filterIndexed { i, w -> i == 0 || i == words.lastIndex || w.lowercase() !in CONNECTORS })
    }
    return keys
  }

  private val ASSIMILATED = Regex("""([ui])(th|dh|sh|ṣ|ṭ|ẓ|t|d|r|z|s|n)\s+(\2)""", RegexOption.IGNORE_CASE)
  private val INITIAL_KH = Regex("""(?<!\p{L})Kh""", RegexOption.IGNORE_CASE)
  private val JOINING_IL = Regex("""(?<=\p{L})(?:il|ir|ith)\s+(\p{L})""")

  internal fun phraseKey(words: List<String>): String = key(words.joinToString(""))

  /** The loose phonetic key described in the class comment. */
  internal fun key(s: String): String {
    val stripped =
      Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() && it.isLetter() }
    var k = stripped
      .replace("ddh", "d") // "Huddham": a doubled d with an h, not the dh sound
      .replace("dh", "z")
      .replace("th", "s")
      .replace("ph", "f")
      .replace("ck", "k")
      .replace('q', 'k')
      .replace('v', 'w')
      .replace('y', 'i')
      .replace('e', 'i')
      .replace('o', 'u')
    k = collapseDoubles(k)
    if (k.length > 3 && k.endsWith("h") && k[k.length - 2] in "aiu") k = k.dropLast(1)
    return k
  }

  private fun collapseDoubles(s: String): String {
    val b = StringBuilder(s.length)
    for (c in s) if (b.isEmpty() || b.last() != c) b.append(c)
    return b.toString()
  }
}
