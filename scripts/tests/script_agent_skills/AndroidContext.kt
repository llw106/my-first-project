package android.content

import android.net.Uri
import java.io.InputStream

open class Context(val contentResolver: ContentResolver = ContentResolver())
open class ContentResolver {
    var content = byteArrayOf()
    open fun openInputStream(uri: Uri): InputStream? = content.inputStream()
}
