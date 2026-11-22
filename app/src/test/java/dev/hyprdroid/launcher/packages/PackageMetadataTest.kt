package dev.hyprdroid.launcher.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PackageMetadataTest {
    @Test
    fun parserRejectsLenientJsonDuplicateFieldsAndTrailingContent() {
        for (text in listOf(
            """{id:"module"}""",
            """{"id":'module'}""",
            """{"id":"module","id":"other"}""",
            """{"id":"module"}{}""",
            """{/*comment*/"id":"module"}""",
        )) {
            assertThrows(text, PackageException::class.java) {
                packageMetadata("Некорректный JSON") { parsePackageJson(text.encodeToByteArray()) }
            }
        }
        assertEquals("module", parsePackageJson("""{"id":"module"}""".encodeToByteArray()).requiredString("id"))
    }
}
