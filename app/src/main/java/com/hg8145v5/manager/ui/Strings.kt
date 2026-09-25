package com.hg8145v5.manager.ui

/** Tiny runtime i18n: English is the base, Arabic is the toggle. */
fun tr(lang: String, en: String, ar: String): String = if (lang == "ar") ar else en

fun isRtl(lang: String) = lang == "ar"
