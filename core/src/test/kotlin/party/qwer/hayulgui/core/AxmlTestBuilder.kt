package party.qwer.hayulgui.core

/**
 * JVM test 용 minimal AXML 생성기 (aapt2 산출물 레이아웃 모사).
 *
 * 구조: FILE hdr → pool(UTF-16/UTF-8) → START_NS(android) → <manifest attrs> →
 *       <application attrs> → END ×2 → END_NS.
 *
 * 속성: TVal.Str(value), TVal.Int(v), ns 기본은 android uri, "auto" 는 ns=-1.
 */
object AxmlTestBuilder {

    sealed class TVal {
        class Str(val v: String) : TVal()
        class IntV(val v: Int) : TVal()
    }

    class TAttr(val name: String, val value: TVal, val ns: String = "android")

    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10

    /** 테스트용 표준 mini manifest: package + versionCode, application 은 debuggable 정도. */
    fun miniManifest(
        packageName: String = "com.example.app",
        versionCode: Int = 1,
        extraManifestAttrs: List<TAttr> = emptyList(),
        appAttrs: List<TAttr> = emptyList(),
        utf8: Boolean = false,
        minSdkVersion: Int? = 26,
    ): ByteArray {
        val pool = mutableListOf(Axml.ANDROID_URI, "manifest", "application",
            "uses-sdk", "minSdkVersion", packageName)
        val mAttrs = ArrayList<ByteArray>()
        mAttrs.add(attr(pool, "package", TVal.Str(packageName), ns = "android"))
        mAttrs.add(attr(pool, "versionCode", TVal.IntV(versionCode), ns = "android"))
        extraManifestAttrs.forEach { mAttrs.add(attr(pool, it.name, it.value, it.ns)) }
        val aAttrs = ArrayList<ByteArray>()
        appAttrs.forEach { aAttrs.add(attr(pool, it.name, it.value, it.ns)) }
        val usSdk: ByteArray? = minSdkVersion?.let {
            el(pool.indexOf("uses-sdk"), listOf(attr(pool, "minSdkVersion", TVal.IntV(it), ns = "android")))
        }
        return assemble(pool, mAttrs, aAttrs, utf8, usSdk)
    }

    fun parse(bytes: ByteArray): Axml = Axml.parse(bytes)

    private fun attr(pool: MutableList<String>, name: String, value: TVal, ns: String): ByteArray {
        val b = ByteArray(20)
        putInt(b, 0, when (ns) {
            "android" -> internAdd(pool, Axml.ANDROID_URI)
            else -> -1
        })
        putInt(b, 4, internAdd(pool, name))
        putInt(b, 8, -1)
        putShort(b, 12, 8)
        when (value) {
            is TVal.Str -> {
                b[15] = TYPE_STRING.toByte()
                putInt(b, 16, internAdd(pool, value.v))
            }
            is TVal.IntV -> {
                b[15] = TYPE_INT_DEC.toByte()
                putInt(b, 16, value.v)
            }
        }
        return b
    }

    private fun el(nameRef: Int, attrs: List<ByteArray>, children: List<ByteArray> = emptyList()): ByteArray {
        val head = 16 + 20
        val body = head + attrs.size * 20
        val b = ByteArray(body)
        putShort(b, 0, 0x0102); putShort(b, 2, 16); putInt(b, 4, body); putInt(b, 8, 2); putInt(b, 12, -1)
        putInt(b, 16, -1); putInt(b, 20, nameRef)
        putShort(b, 24, 20); putShort(b, 26, 20); putShort(b, 28, attrs.size)
        var o = 36
        attrs.forEach { it.copyInto(b, o); o += 20 }
        return b + children.fold(ByteArray(0)) { x, y -> x + y } +
            ByteArray(24).also {
                putShort(it, 0, 0x0103); putShort(it, 2, 16); putInt(it, 4, 24); putInt(it, 8, 3); putInt(it, 12, -1)
                putInt(it, 16, -1); putInt(it, 20, nameRef)
            }
    }

    private fun assemble(poolStrings: List<String>, mAttrs: List<ByteArray>, aAttrs: List<ByteArray>, utf8: Boolean, child: ByteArray? = null): ByteArray {
        val manifestRef = poolStrings.indexOf("manifest")
        val appRef = poolStrings.indexOf("application")
        val uriRef = poolStrings.indexOf(Axml.ANDROID_URI)

        fun nodeChunk(type: Int, a: Int, b2: Int): ByteArray {
            val b = ByteArray(24)
            putShort(b, 0, type); putShort(b, 2, 16); putInt(b, 4, 24); putInt(b, 8, 3); putInt(b, 12, -1)
            putInt(b, 16, a); putInt(b, 20, b2)
            return b
        }

        val pool = Axml.StringPool.encode(poolStrings, utf8)
        val header = ByteArray(16).also { putShort(it, 0, 3); putShort(it, 2, 16) }
        val manifestBody = el(appRef, aAttrs) + (child ?: ByteArray(0))
        val parts = listOf(
            header, pool,
            nodeChunk(0x0100, -1, uriRef),
            el(manifestRef, mAttrs, listOf(manifestBody)),
            nodeChunk(0x0101, -1, uriRef),
        )
        val total = parts.sumOf { it.size }
        putInt(header, 4, total)
        return parts.fold(ByteArray(0)) { x, y -> x + y }
    }

    private fun internAdd(pool: MutableList<String>, s: String): Int {
        pool.indexOf(s).let { if (it >= 0) return it }
        pool.add(s)
        return pool.size - 1
    }

    private fun putShort(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte()
    }

    private fun putInt(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte()
        b[o + 2] = (v shr 16).toByte(); b[o + 3] = (v shr 24).toByte()
    }
}
