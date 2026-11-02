package android.net

// URL parsing is outside this regression suite; accidental use must fail explicitly.
class Uri {
    val scheme: String? get() = error("Unexpected URI access")
    val host: String? get() = error("Unexpected URI access")
    val encodedPath: String? get() = error("Unexpected URI access")
    fun buildUpon(): Builder = error("Unexpected URI access")
    class Builder {
        fun encodedPath(path: String): Builder = error("Unexpected URI access")
        fun clearQuery(): Builder = error("Unexpected URI access")
        fun appendQueryParameter(key: String, value: String): Builder = error("Unexpected URI access")
        fun build(): Uri = error("Unexpected URI access")
    }
    companion object {
        fun parse(value: String): Uri = error("Unexpected URI access")
        fun encode(value: String): String = error("Unexpected URI access")
    }
}
