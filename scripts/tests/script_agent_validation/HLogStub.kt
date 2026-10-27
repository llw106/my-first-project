package h.Hchat.utils

object HLog {
    val failures = mutableListOf<Throwable>()
    fun e(message: String, error: Throwable) {
        failures += error
    }
}
