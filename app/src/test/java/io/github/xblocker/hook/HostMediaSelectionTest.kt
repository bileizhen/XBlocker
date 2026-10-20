package io.github.xblocker.hook

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Modifier

class HostMediaSelectionTest {
    @Test fun selectsHighestMp4AndNeverAnHlsOrForeignHost() {
        val low = HostMediaSelection.Variant("https://video.twimg.com/ext_tw_video/1/vid/320x180/a.mp4", 256000, "video/mp4")
        val high = low.copy(url = "https://video.twimg.com/ext_tw_video/1/vid/1280x720/a.mp4", bitrate = 2176000)
        val list = listOf(high.copy(url = "https://evil.example/a.mp4", bitrate = 99999999), low,
            high.copy(url = "https://video.twimg.com/a.m3u8", mime = "application/x-mpegURL"), high)
        assertEquals(high, HostMediaSelection.highestMp4(list))
        assertNull(HostMediaSelection.highestMp4(listOf(list[0], list[2])))
    }
    @Test fun photosUseOriginalSizeAndPreserveFormat() {
        assertEquals("https://pbs.twimg.com/media/Abc?format=png&name=orig", HostMediaSelection.originalPhoto("https://pbs.twimg.com/media/Abc.png:large"))
        assertEquals("https://pbs.twimg.com/media/Def?format=webp&name=orig", HostMediaSelection.originalPhoto("https://pbs.twimg.com/media/Def?format=webp&name=small"))
    }
    @Test fun unsupportedModelsNeverDownloadAThumbnailAsVideo() {
        assertNull(HostMediaSelection.fromModel(null, schema()))
        assertNull(HostMediaSelection.fromModel("https://pbs.twimg.com/media/thumbnail.jpg", schema()))
    }

    @Test fun shuffledObfuscatedFieldNamesAreMatchedByTheirPrintedSemantics() {
        val image = Image("post-photo", "https://pbs.twimg.com/media/Photo.jpg")
        val found = HostMediaSelection.fromModel(image, schema())!!
        assertEquals("post-photo", found.mediaId)
        assertEquals("https://pbs.twimg.com/media/Photo?format=jpg&name=orig", found.url)
        assertEquals("image", found.type)
    }

    @Test fun inheritedModelFieldsRetainTheirDeclaringOwner() {
        val found = HostMediaSelection.fromModel(LegacyImage("legacy", "https://pbs.twimg.com/media/Old.png"), schema())!!
        assertEquals("legacy", found.mediaId)
        assertTrue(found.url.endsWith("format=png&name=orig"))
    }

    @Test fun videoUsesItsOwnIdAndHighestProvenVariantNotItsPreview() {
        val preview = Image("thumbnail", "https://pbs.twimg.com/media/Preview.jpg")
        val video = Video("actual-video", preview, listOf(
            VariantModel("video/mp4", 256000, "https://video.twimg.com/a.mp4"),
            VariantModel("video/mp4", 2176000, "https://video.twimg.com/b.mp4"),
            VariantModel("application/x-mpegURL", 9999999, "https://video.twimg.com/c.m3u8")), "12345")
        val found = HostMediaSelection.fromModel(video, schema())!!
        assertEquals("actual-video", found.mediaId)
        assertEquals("12345", found.tweetId)
        assertEquals("https://video.twimg.com/b.mp4", found.url)
    }

    @Test fun missingMp4CannotFallBackToPreview() {
        val video = Video("v", Image("i", "https://pbs.twimg.com/media/P.jpg"), listOf(
            VariantModel("application/x-mpegURL", 1000000, "https://video.twimg.com/a.m3u8")))
        assertNull(HostMediaSelection.fromModel(video, schema()))
        assertSame(video, HostMediaSelection.findInCallback(Capture(video), schema()))
    }

    @Test fun foreignVariantAndDuplicateFieldValuesAreRejected() {
        assertNull(HostMediaSelection.fromModel(Video("v", null, listOf("https://video.twimg.com/a.mp4")), schema()))
        // Matching two candidate fields is ambiguous even when their values happen to be equal.
        val url = "https://pbs.twimg.com/media/P.jpg"
        assertNull(HostMediaSelection.fromModel(Image(url, url), schema()))
    }

    @Test fun nullableBitrateAndGifStaySupported() {
        val video = Gif("gif", listOf(VariantModel("video/mp4", null, "https://video.twimg.com/g.mp4")))
        assertEquals("https://video.twimg.com/g.mp4", HostMediaSelection.fromModel(video, schema())?.url)
    }

    @Test fun arbitraryAltTextPunctuationDoesNotBreakImageOrVideoSelection() {
        val image = Image("photo", "https://pbs.twimg.com/media/Alt.jpg", "an unmatched ( bracket, and imageUrl=fake")
        assertNotNull(HostMediaSelection.fromModel(image, schema()))
        val video = Video("video", image, listOf(VariantModel("video/mp4", 1000, "https://video.twimg.com/v.mp4")))
        assertEquals("https://video.twimg.com/v.mp4", HostMediaSelection.fromModel(video, schema())?.url)
    }

    @Test fun callbackNeverExpandsGalleryCollectionOrPicksBetweenDifferentModels() {
        val first = Image("one", "https://pbs.twimg.com/media/One.jpg")
        val visible = Image("two", "https://pbs.twimg.com/media/Two.jpg")
        assertSame(visible, HostMediaSelection.findInCallback(Capture(visible, listOf(first, visible)), schema()))
        assertNull(HostMediaSelection.findInCallback(Capture(first, visible), schema()))
        assertNull(HostMediaSelection.findInCallback(Capture(listOf(first, visible)), schema()))
    }

    @Test fun malformedOrOversizedDescriptionsFailClosed() {
        val invalid = object : Image("a", "https://pbs.twimg.com/media/A.jpg") {
            override fun toString() = "MediaContentImage(mediaId=a, mediaId=a, imageUrl=https://pbs.twimg.com/media/A.jpg)"
        }
        assertNull(HostMediaSelection.fromModel(invalid, schema()))
        assertNull(HostMediaSelection.fromModel(Image("a", "https://pbs.twimg.com/media/A.jpg", "a".repeat(65536)), schema()))
    }

    @Test fun discoveryCacheRoundTripPreservesCandidateOwnersAndTypes() {
        val original = schema()
        assertEquals(original, MediaModelSchema.fromJson(original.toJson()))
        val imageOnly = original.copy(models = original.models.filter { it.kind == "image" }, variants = emptyList())
        assertEquals(imageOnly, MediaModelSchema.fromJson(imageOnly.toJson()))
    }

    private fun schema(): MediaModelSchema = MediaModelSchema(
        listOf(spec(Image::class.java, "image", "MediaContentImage("), spec(Video::class.java, "video", "MediaContentVideo("),
            spec(Gif::class.java, "gif", "MediaContentGif(")), listOf(spec(VariantModel::class.java, "variant", "MediaVariant(")))

    private fun spec(type: Class<*>, kind: String, prefix: String) = MediaModelClass(type.name, kind, prefix,
        type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.reversed().map {
            MediaModelField(type.name, it.name, it.type.name)
        })

    private open class Image(private val zz: String, private val aa: String, private val xy: String? = null) {
        override fun toString() = "MediaContentImage(mediaId=$zz, imageUrl=$aa, altText=$xy)"
    }
    private class LegacyImage(id: String, url: String) : Image(id, url)
    private class VariantModel(private val aa: String, private val zz: Int?, private val yy: String) {
        override fun toString() = "MediaVariant(url=$yy, bitRate=$zz, contentType=$aa)"
    }
    private class Video(private val qq: String, private val aa: Image?, private val zz: List<Any>, private val ss: String? = null) {
        override fun toString() = "MediaContentVideo(mediaId=$qq, previewImage=$aa, variants=$zz, sourcePostIdentifier=$ss)"
    }
    private class Gif(private val cc: String, private val bb: List<Any>) {
        override fun toString() = "MediaContentGif(mediaId=$cc, variants=$bb)"
    }
    private class Capture(private val first: Any?, private val second: Any? = null) : () -> Any? {
        override fun invoke() = first
    }
}
