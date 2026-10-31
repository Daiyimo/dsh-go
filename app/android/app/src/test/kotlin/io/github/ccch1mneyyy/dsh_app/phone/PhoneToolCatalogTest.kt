package io.github.ccch1mneyyy.dsh_app.phone

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/**
 * The tool list (names, descriptions, schemas) enters every model request, so a single changed
 * description invalidates DeepSeek's KV cache from the first changed token. This hash is the pin
 * the catalog header promises: change it deliberately, never by accident.
 */
class PhoneToolCatalogTest {
    private fun listHash(): String {
        val bytes = Json.write(PhoneToolCatalog.listJson()).toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    @Test fun theToolListHashOnlyChangesOnPurpose() {
        assertEquals(
            "the phone tool list changed; if this is deliberate, update this hash — every model request carries it",
            "d0cfdf018614c3ccb820a17edf0ea1dbdde048a106b20df79077b61f07017e92",
            listHash(),
        )
    }

    @Test fun tapXySpeaksNormalizedCoordinatesAndNamesTheCanvasPath() {
        val tool = PhoneToolCatalog.tools.single { it.name == PhoneToolCatalog.TAP_XY }
        assertEquals(listOf("observation", "x", "y"), tool.required)
        val x = tool.properties["x"] as Map<*, *>
        val y = tool.properties["y"] as Map<*, *>
        assertEquals(0, x["minimum"])
        assertEquals(1000, x["maximum"])
        assertEquals(0, y["minimum"])
        assertEquals(1000, y["maximum"])
        assertTrue(tool.description.contains("0-1000"))
        assertFalse(tool.description.contains("像素坐标"))
        val screen = PhoneToolCatalog.tools.single { it.name == PhoneToolCatalog.SCREEN }
        assertTrue(screen.description.contains("自动附上截图"))
    }
}
