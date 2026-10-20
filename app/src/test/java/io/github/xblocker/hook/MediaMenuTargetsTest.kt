package io.github.xblocker.hook

import org.junit.Assert.*
import org.junit.Test

class MediaMenuTargetsTest {
    private fun schema() = MediaModelSchema(listOf(MediaModelClass("host.Image", "image", "MediaContentImage(",
        listOf(MediaModelField("host.Image", "url", "java.lang.String")))), emptyList())
    private fun targets() = MediaMenuTargets(schema(), listOf("Lhost/Menu;->a()V"), null, null, "Lhost/Sheet;->b()V",
        "Lhost/Item;-><init>()V", "Lhost/Item;->id:Ljava/lang/Object;", "Lhost/List;->convert()V", null, null, null,
        listOf("Lhost/Dropdown;->row()V"), "Lhost/Icons;->download:Lhost/Icon;")
    @Test fun cachedTargetsKeepLegacyRoutesAndNativeIcon() {
        assertEquals(targets(), MediaMenuTargets.fromJson(targets().toJson()))
    }
    @Test fun partialImageSchemaDoesNotClaimVideoSupport() {
        val capabilities = targets().capabilities()
        assertEquals(false, capabilities["videoMenus"])
        assertEquals(true, capabilities["photoMenus"])
        assertEquals(false, targets().copy(legacyRows = emptyList()).capabilities()["photoMenus"])
    }
}
