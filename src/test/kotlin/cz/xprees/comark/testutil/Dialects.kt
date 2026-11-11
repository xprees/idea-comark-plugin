package cz.xprees.comark.testutil

import cz.xprees.comark.settings.ComarkSettings

/** Runs [block] with the parser [dialect] selected, restoring the previous one afterwards. */
fun <T> withDialect(dialect: ComarkSettings.Dialect, block: () -> T): T {
    val settings = ComarkSettings.getInstance()
    val before = settings.dialect
    settings.dialect = dialect
    try {
        return block()
    } finally {
        settings.dialect = before
    }
}
