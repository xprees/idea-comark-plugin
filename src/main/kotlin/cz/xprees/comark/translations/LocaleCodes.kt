package cz.xprees.comark.translations

import java.util.Locale

/** Locale codes as directory names: an ISO 639-1 language, optionally with a region or script (`en`, `pt-br`, `zh_Hant`). */
internal object LocaleCodes {
    private val SHAPE = Regex("""^([A-Za-z]{2})(?:[-_](?:[A-Za-z]{2}|[A-Za-z]{4}|\d{3}))?$""")

    /** ISO 639-1 two-letter language codes. */
    private val LANGUAGES = """
        aa ab ae af ak am an ar as av ay az ba be bg bh bi bm bn bo br bs ca ce ch co cr cs cu cv cy da de dv dz ee el en eo es et eu
        fa ff fi fj fo fr fy ga gd gl gn gu gv ha he hi ho hr ht hu hy hz ia id ie ig ii ik io is it iu ja jv ka kg ki kj kk kl km kn
        ko kr ks ku kv kw ky la lb lg li ln lo lt lu lv mg mh mi mk ml mn mr ms mt my na nb nd ne ng nl nn no nr nv ny oc oj om or os
        pa pi pl ps pt qu rm rn ro ru rw sa sc sd se sg si sk sl sm sn so sq sr ss st su sv sw ta te tg th ti tk tl tn to tr ts tt tw
        ty ug uk ur uz ve vi vo wa wo xh yi yo za zh zu
    """.trim().split(Regex("""\s+""")).toSet()

    fun isCode(name: String): Boolean {
        val match = SHAPE.matchEntire(name) ?: return false
        return match.groupValues[1].lowercase(Locale.ROOT) in LANGUAGES
    }
}
