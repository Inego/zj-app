package inego.zungjunggame.state

/**
 * Field-framed text record shared by every hand-rolled [Schema].
 *
 * One value is a sequence of length-prefixed UTF-8 fields (`<chars>:<text>`).
 * Length framing needs no escaping, and the whole blob is decoded to a string
 * before reading, so a char count is a byte-safe boundary for any script.
 *
 * Pure Kotlin (no `java.io`, no libGDX) so the same schema compiles for desktop,
 * Android, and Wasm. This is the single owner of the framing format: schemas
 * write fields, they never hand-roll their own delimiters.
 */
class RecordWriter {
    private val text = StringBuilder()

    fun field(value: String): RecordWriter = apply {
        text.append(value.length).append(':').append(value)
    }

    fun int(value: Int): RecordWriter = field(value.toString())

    fun boolean(value: Boolean): RecordWriter = field(if (value) "1" else "0")

    fun float(value: Float): RecordWriter = field(value.toString())

    fun long(value: Long): RecordWriter = field(value.toString())

    fun build(): ByteArray = text.toString().toByteArray(Charsets.UTF_8)
}

/**
 * Reader for a [RecordWriter] blob.
 *
 * Every accessor throws [IllegalArgumentException] on a malformed field, so a
 * [Schema.decode] body may parse optimistically and return null from its catch:
 * truncated, reordered, or non-numeric data can never be mistaken for a value.
 */
class RecordReader(bytes: ByteArray) {
    private val text = bytes.toString(Charsets.UTF_8)
    private var position = 0

    /** True once every field has been consumed. */
    fun atEnd(): Boolean = position >= text.length

    fun field(): String {
        val colon = text.indexOf(':', position)
        require(colon >= 0) { "missing field delimiter at $position" }
        val length = text.substring(position, colon).toIntOrNull()
            ?: throw IllegalArgumentException("not a field length at $position")
        require(length in 0..MAX_FIELD) { "field out of range: $length" }
        position = colon + 1
        require(position + length <= text.length) { "truncated field at $position" }
        val value = text.substring(position, position + length)
        position += length
        return value
    }

    fun int(): Int = field().toIntOrNull() ?: throw IllegalArgumentException("not an int")

    fun boolean(): Boolean = when (field()) {
        "1" -> true
        "0" -> false
        else -> throw IllegalArgumentException("not a boolean")
    }

    fun float(): Float = field().toFloatOrNull() ?: throw IllegalArgumentException("not a float")

    fun long(): Long = field().toLongOrNull() ?: throw IllegalArgumentException("not a long")

    /** A list length bounded by [max], so corrupt data cannot allocate wildly. */
    fun count(max: Int): Int {
        val value = int()
        require(value in 0..max) { "count out of range: $value" }
        return value
    }

    private companion object {
        const val MAX_FIELD = 4096
    }
}
