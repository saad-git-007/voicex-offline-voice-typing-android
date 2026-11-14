package com.voicex.app

import com.voicex.app.output.Transliteration
import org.junit.Assert.assertEquals
import org.junit.Test

class TransliterationTest {
  private fun t(s: String) = Transliteration.apply(s)

  @Test fun commonSpellingsBecomeOfficial() {
    assertEquals("Ḥaḍrat Khalīfatul Masīḥ gave the Khuṭbā.", t("Hazrat Khalifatul Masih gave the Khutba."))
    assertEquals("Ḥaḍrat Khalīfatul Masīḥ", t("Hadhrat Khalifa tul Maseeh"))
    assertEquals("Ḥuḍūr said", t("Huzoor said"))
    assertEquals("the Jamā‘at", t("the Jamaat"))
    assertEquals("the Jamā‘at", t("the Jama'at"))
    assertEquals("Jalsa Sālāna in Qādiān", t("Jalsa Salana in Qadian"))
    assertEquals("Ijtimā‘ of Khuddāmul Ahmadiyya", t("Ijtema of Khuddam-ul-Ahmadiyya"))
    assertEquals("Lajna Imāillāh and Anṣārullāh", t("Lajna Imaillah and Ansarullah"))
    assertEquals("Aṭfālul Aḥmadiyya", t("Atfal ul Ahmadiyya"))
    assertEquals("Qur’ān", t("Quran"))
    assertEquals("‘Īd", t("Eid"))
    assertEquals("Bai‘at", t("Baiat"))
    assertEquals("Ḥadīth", t("Hadees"))
  }

  @Test fun izafatAndConnectors() {
    assertEquals("Waqf Nau", t("Waqf-e-Nau"))
    assertEquals("Waqf Nau", t("Waqfe Nau"))
    assertEquals("Majlis Shūrā", t("Majlis-e-Shura"))
    assertEquals("Taḥrīk-i-Jadīd", t("Tehrik-e-Jadid"))
    assertEquals("Taḥrīk-i-Jadīd", t("Tahrik Jadid"))
    assertEquals("Iṣlāḥ-o-Irshād", t("Islah o Irshad"))
    assertEquals("Masīḥ Mau‘ūd", t("Masih-e-Maud"))
  }

  @Test fun longestPhraseWins() {
    assertEquals("Khalīfatul Masīḥil Awwal", t("Khalifatul Masih ul Awwal"))
    assertEquals("Khalīfatul Masīḥir Rābi‘", t("Khalifatul Masihir Rabi"))
    assertEquals("Wakīlut Tabshīr", t("Wakil ul Tabshir"))
    assertEquals("Wakīlut Tabshīr", t("Wakilut Tabshir"))
    assertEquals("Wakīlul Māl III", t("Wakilul Mal III"))
    assertEquals("Majlis Mushāwarat", t("majlis mushawarat"))
  }

  @Test fun punctuationAndPossessivesKept() {
    assertEquals("Ḥuḍūr’s message, Jamā‘at's work.", t("Huzoor’s message, Jamaat's work."))
    // A phrase never spans punctuation: "Waqf. Nau" stays two words.
    assertEquals("Waqf. Nau", t("Waqf. Nau"))
  }

  @Test fun englishWordsUntouched() {
    val s = "I am at the mall with my ID, amen. Allah knows. The mouse ate sales data, Rabbi."
    assertEquals(s, t(s))
    assertEquals("Hello world, how are you today?", t("Hello world, how are you today?"))
    assertEquals("", t(""))
  }

  @Test fun otherScriptsUntouched() {
    val ur = "حضرت خلیفۃ المسیح نے خطبہ دیا"
    assertEquals(ur, t(ur))
  }

  @Test fun realTranscript() {
    assertEquals(
      "Majlis Khuddāmul Ahmadiyya will have to tell Ṣadr Ṣāḥib about which Majdis are they going to and how they're going to work with Ṣan‘at-o-Tijārat.",
      t("Majlis Khudna Mul Ahmadiyya will have to tell Sadhar Sahib about which Majdis are they going to and how they're going to work with San'at-o-Tijarat."),
    )
  }

  @Test fun greeting() {
    for (said in listOf("Assalamu alaikum", "Assalam o Alaikum", "As-salamu alaykum", "Asalam alaikum", "Salaam alaikum", "Assalamualaikum"))
      assertEquals(said, "Assalām ‘Alaikum, everyone.", t("$said, everyone."))
  }

  @Test fun reply() {
    for (said in listOf("Walaikum salam", "Wa alaikum salam", "Wa alaikum assalam", "Walaikum assalam", "Wa alaykum as-salam",
      "Walaikumus salam", "Wa'alaikum salaam", "Waalaikumsalam"))
      assertEquals(said, "Wa'laikum Salām, brother.", t("$said, brother."))
  }

  @Test fun thanks() {
    for (said in listOf("Jazakallah", "JazakAllah", "Jazak Allah", "Jazak'Allah", "jazakalla", "Jazaak Allah"))
      assertEquals(said, "Jazāk'Allāh for the help.", t("$said for the help."))
    assertEquals("Jazāk'Allāh Khair", t("Jazakallah Khair"))
  }

  @Test fun reportedMishearings() {
    assertEquals("Jazāk'Allāh, see you at Jalsa Sālāna.", t("Jazakullah, see you at Jaza Solana."))
    assertEquals("Jalsa Sālāna", t("Jalsa Solana"))
    assertEquals("Jalsa Sālāna", t("Jalsa Salana"))
    assertEquals("Our Ḥalqa meets on Friday.", t("Our Halka meets on Friday."))
    assertEquals("Ḥalqa", t("Halqa"))
  }

  @Test fun jadidAndAfsar() {
    assertEquals(
      "Taḥrīk-i-Jadīd and Waqf Jadīd will be there too along with their Afsar Jalsa Gāh.",
      t("Tarik Jade and Wakse Jade will be there too along with their Afsir Jalsa Gāh."),
    )
    assertEquals("Afsar Jalsa Gāh", t("Afsur Jalsa Gah"))
    assertEquals("Afsar", t("Afsar"))
    assertEquals("A jade ring", t("A jade ring"))
  }

  @Test fun khHeardAsH() {
    assertEquals("Khuddām", t("Huddham"))
    assertEquals("Khuddām", t("huddam"))
    assertEquals("Majlis Khuddāmul Ahmadiyya meets today.", t("Majlis Huddham ul Ahmadiyya meets today."))
    assertEquals("Majlis Khuddāmul Ahmadiyya", t("Majlis Huddhamul Ahmadiyya"))
    assertEquals("Khilāfat", t("Hilafat"))
  }

  @Test fun nearMissPhrasesOnly() {
    assertEquals("Khuddāmul Ahmadiyya", t("Khudna Mul Ahmadiyya"))
    assertEquals("Khalīfatul Masīḥ", t("Khalifatul Masiha"))
    // Single words are never matched loosely, and short phrases need an exact key.
    assertEquals("A Muslim shared the parallel results.", t("A Muslim shared the parallel results."))
    assertEquals("abuse occurs in the area of products", t("abuse occurs in the area of products"))
  }

  @Test fun everyTermMapsToItself() {
    for (term in Transliteration.TERMS + Transliteration.DERIVED_TERMS) assertEquals(term, t(term))
  }
}
