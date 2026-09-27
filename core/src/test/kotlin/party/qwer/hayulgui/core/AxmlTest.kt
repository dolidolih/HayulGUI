package party.qwer.hayulgui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AxmlTest {

    @Test fun roundtripMini() {
        val axml = AxmlTestBuilder.miniManifest(packageName = "com.example.app", versionCode = 42)
        val doc = Axml.parse(axml)
        assertEquals("com.example.app", doc.readAttribute("manifest", "package"))
        assertEquals("42", doc.readAttribute("manifest", "versionCode"))
        assertNull(doc.readAttribute("manifest", "sharedUserId"))
    }

    @Test fun patchAddsSharedUserIdAndFactory() {
        val axml = AxmlTestBuilder.miniManifest()
        val doc = Axml.parse(axml)
        doc.setStringAttribute("manifest", "sharedUserId", "party.qwer.irisgui")
        doc.setStringAttribute("application", "appComponentFactory", "x.y.Factory")
        val out = doc.pack()
        val again = Axml.parse(out)
        assertEquals("party.qwer.irisgui", again.readAttribute("manifest", "sharedUserId"))
        assertEquals("x.y.Factory", again.readAttribute("application", "appComponentFactory"))
    }

    @Test fun repatchReplacesValue() {
        val axml = AxmlTestBuilder.miniManifest()
        var doc = Axml.parse(axml)
        doc.setStringAttribute("manifest", "sharedUserId", "aaa")
        doc = Axml.parse(doc.pack())
        doc.setStringAttribute("manifest", "sharedUserId", "bbb")
        doc = Axml.parse(doc.pack())
        assertEquals("bbb", doc.readAttribute("manifest", "sharedUserId"))
        // sharedUserId 속성 갯자는 유지 = attribute 교체 확인
        val count = doc.chunks.filter { it.type == 0x0102 }.sumOf { c ->
            val e = c.data
            val n = shortAtLocal(e, 28)
            val start = shortAtLocal(e, 24)
            val size = shortAtLocal(e, 26)
            (0 until n).count { i ->
                doc.attrNameAt(e, 16 + start + size * i) == "sharedUserId"
            }
        }
        assertEquals(1, count)
    }

    @Test fun utf8PoolPatch() {
        val axml = AxmlTestBuilder.miniManifest(utf8 = true)
        val doc = Axml.parse(axml)
        assertEquals("1", doc.readAttribute("manifest", "versionCode"))
        doc.setStringAttribute("manifest", "sharedUserId", "grp.utf8")
        val again = Axml.parse(doc.pack())
        assertEquals("grp.utf8", again.readAttribute("manifest", "sharedUserId"))
    }

    @Test fun realKakaoManifest() {
        // 제3자 바이너리는 repo 에 포함하지 않는다 — 로컬 testdata 가 있으면 검증, 없으면 skip.
        val f = java.io.File("ref_project/testdata/kakao_manifest.axml")
            .let { if (it.exists()) it else java.io.File("../ref_project/testdata/kakao_manifest.axml") }
        org.junit.Assume.assumeTrue("kakao_manifest.axml not present locally", f.exists())
        val bytes = f.readBytes()
        val doc = Axml.parse(bytes)
        val pkg = doc.readAttribute("manifest", "package")
        assertEquals("com.kakao.talk", pkg)
        assertNotNull(doc.readAttribute("manifest", "versionCode"))
        assertNull(doc.readAttribute("manifest", "sharedUserId"))

        doc.setStringAttribute("manifest", "sharedUserId", "party.qwer.test")
        doc.setStringAttribute("application", "appComponentFactory", "party.qwer.hayulgui.stub.PatcherAppComponentFactory")
        val packed = doc.pack()
        val again = Axml.parse(packed)
        assertEquals("party.qwer.test", again.readAttribute("manifest", "sharedUserId"))
        assertEquals("party.qwer.hayulgui.stub.PatcherAppComponentFactory",
            again.readAttribute("application", "appComponentFactory"))
        assertEquals(pkg, again.readAttribute("manifest", "package"))

        // pack 이후에도 전체 구조 유지: START_EL manifest 검색 가능 + pool 에 attr 문자열
        assertTrue(again.pool.items.contains("sharedUserId"))
    }

    private fun shortAtLocal(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun intAtLocal(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun poolName(doc: Axml, ref: Int): String? = doc.pool.at(ref)
}
