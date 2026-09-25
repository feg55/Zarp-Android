package io.github.feg55.zarp.core

import java.io.File
import java.util.Locale

/** Loads the real language files from src/main/assets, in English. */
object TestLang {
    fun english() {
        L.init(open = { File("src/main/assets", it).inputStream() }, setting = "en", systemLocale = Locale.ENGLISH)
    }
}
