package android.database

interface Cursor {
    val columnNames: Array<String>
    val count: Int
    val position: Int
    val isClosed: Boolean
    fun getColumnIndex(name: String): Int
    fun moveToPosition(position: Int): Boolean
    fun moveToNext(): Boolean
    fun getType(index: Int): Int
    fun getLong(index: Int): Long
    fun getDouble(index: Int): Double
    fun getString(index: Int): String?
    fun getBlob(index: Int): ByteArray?
    fun close()

    companion object {
        const val FIELD_TYPE_NULL = 0
        const val FIELD_TYPE_INTEGER = 1
        const val FIELD_TYPE_FLOAT = 2
        const val FIELD_TYPE_STRING = 3
        const val FIELD_TYPE_BLOB = 4
    }
}

open class MatrixCursor(
    override val columnNames: Array<String>,
    initialCapacity: Int = 16
) : Cursor {
    private val rows = ArrayList<Array<Any?>>(initialCapacity)
    override val count get() = rows.size
    final override var position = -1
        private set
    final override var isClosed = false
        private set
    var closeCount = 0
        private set

    fun addRow(values: Array<Any?>) {
        check(!isClosed)
        require(values.size == columnNames.size)
        rows.add(values.copyOf())
    }

    override fun getColumnIndex(name: String) = columnNames.indexOfFirst { it.equals(name, true) }

    override fun moveToPosition(position: Int): Boolean {
        check(!isClosed)
        this.position = position.coerceIn(-1, count)
        return position in 0 until count
    }

    override fun moveToNext() = moveToPosition(position + 1)

    protected fun read(index: Int): Any? {
        check(!isClosed)
        check(position in 0 until count)
        return rows[position][index]
    }

    override fun getType(index: Int): Int = when (read(index)) {
        null -> Cursor.FIELD_TYPE_NULL
        is ByteArray -> Cursor.FIELD_TYPE_BLOB
        is Float, is Double -> Cursor.FIELD_TYPE_FLOAT
        is Number -> Cursor.FIELD_TYPE_INTEGER
        else -> Cursor.FIELD_TYPE_STRING
    }

    override fun getLong(index: Int) = (read(index) as Number).toLong()
    override fun getDouble(index: Int) = (read(index) as Number).toDouble()
    override fun getString(index: Int) = read(index)?.toString()
    override fun getBlob(index: Int) = read(index) as ByteArray?
    override fun close() {
        closeCount++
        isClosed = true
    }
}
