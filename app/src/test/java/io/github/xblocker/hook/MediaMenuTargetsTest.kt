package io.github.xblocker.hook

import org.junit.Assert.*
import org.junit.Test

class MediaMenuTargetsTest {
    private fun schema() = MediaModelSchema(listOf(MediaModelClass("host.Image", "image", "MediaContentImage(",
        listOf(MediaModelField("host.Image", "url", "java.lang.String")))), emptyList())
    private fun videoSchema() = MediaModelSchema(listOf(MediaModelClass("host.Video", "video", "MediaContentVideo(",
        listOf(MediaModelField("host.Video", "url", "java.lang.String")))), listOf(MediaModelClass("host.Variant",
        "variant", "MediaVariant(", listOf(MediaModelField("host.Variant", "url", "java.lang.String")))))
    private fun targets() = MediaMenuTargets(schema(), listOf("Lhost/Menu;->a()V"), null, null, "Lhost/Sheet;->b()V",
        "Lhost/Item;-><init>()V", "Lhost/Item;->id:Ljava/lang/Object;", "Lhost/List;->convert()V", null, null, null,
        listOf("Lhost/Dropdown;->row()V"), "Lhost/Icons;->download:Lhost/Icon;")
    private fun videoTargets() = targets().copy(models = videoSchema(), videoMenus = emptyList(), sheetRender = null,
        videoTrays = listOf("Lhost/Tray;->c()V"), videoRows = listOf("Lhost/Rows;->d()V"))
    @Test fun cachedTargetsKeepLegacyRoutesAndNativeIcon() {
        assertEquals(targets(), MediaMenuTargets.fromJson(targets().toJson()))
    }
    @Test fun cachedTargetsKeepImmersiveTrayRoutes() {
        assertEquals(videoTargets(), MediaMenuTargets.fromJson(videoTargets().toJson()))
    }
    @Test fun partialImageSchemaDoesNotClaimVideoSupport() {
        val capabilities = targets().capabilities()
        assertEquals(false, capabilities["videoMenus"])
        assertEquals(true, capabilities["photoMenus"])
        assertEquals(false, targets().copy(legacyRows = emptyList()).capabilities()["photoMenus"])
    }
    @Test fun profileTargetsKeepTrailingButtonAndMenuRoutes() {
        val profile = ProfileMediaTargets(listOf("Lhost/Row;->e()V"), "host.Button",
            "Lhost/Button;-><init>()V", "Lhost/Immutable;->p()V", "host.Operation", "name",
            "Lhost/Follow;->k()V", "Lhost/Render;->c()V", "Lhost/Empty;->a:Lhost/Empty;",
            "host.MenuItem", "Lhost/MenuItem;-><init>()V", "host.MenuModel", "Lhost/MenuRender;->j()V",
            "Lhost/Icons;->download:Lhost/Icon;")
        assertEquals(profile, ProfileMediaTargets.fromJson(profile.toJson()))
    }
    @Test fun immersiveTrayNeedsBothComposableAndRowList() {
        assertEquals(true, videoTargets().capabilities()["videoMenus"])
        assertEquals(false, videoTargets().copy(videoRows = emptyList()).capabilities()["videoMenus"])
        assertEquals(false, videoTargets().copy(videoTrays = emptyList()).capabilities()["videoMenus"])
    }
}
