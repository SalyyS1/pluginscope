package com.salyvn.pluginscope

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/**
 * Packs written to disk for the validator tests.
 *
 * They are real files rather than a stubbed source, because the checks read bytes, PNG headers and
 * directory shapes; anything less would be testing a different program. The PNGs are encoded by
 * ImageIO so their headers are genuine.
 */
internal object FixturePacks {

    private const val NAMESPACE = "testpack"

    /** The format that removed `overrides`, used as the declared format and the target throughout. */
    const val FORMAT = 46

    /** A pack with nothing wrong with it, which is the fixture that matters most. */
    fun clean(root: Path): Path {
        val pack = root.resolve("clean")
        write(pack, "pack.mcmeta", """{"pack": {"pack_format": $FORMAT, "description": "clean"}}""")
        wand(pack)
        write(pack, "assets/$NAMESPACE/items/wand.json", itemDefinition(0, listOf("\"one\"", "[\"two\", \"three\"]")))
        write(
            pack,
            "assets/$NAMESPACE/sounds.json",
            """{"$NAMESPACE.whoosh": {"sounds": [{"name": "$NAMESPACE:custom/whoosh"}]}}""",
        )
        bytes(pack, "assets/$NAMESPACE/sounds/custom/whoosh.ogg", "OggS".toByteArray())
        write(
            pack,
            "assets/$NAMESPACE/font/custom.json",
            """
            {"providers": [{"type": "bitmap", "file": "$NAMESPACE:font/glyphs.png", "ascent": 8, "chars": ["a"]}]}
            """.trimIndent(),
        )
        png(pack, "assets/$NAMESPACE/textures/font/glyphs.png", 16, 16)
        write(
            pack,
            "assets/$NAMESPACE/atlases/blocks.json",
            """{"sources": [{"type": "minecraft:single", "resource": "$NAMESPACE:item/wand"}]}""",
        )
        return pack
    }

    /** One instance of every defect the validator claims to find, and nothing else. */
    fun broken(root: Path): Path {
        val pack = root.resolve("broken")
        write(
            pack,
            "pack.mcmeta",
            """
            {
              "pack": {
                "pack_format": "$FORMAT",
                "min_format": 69,
                "supported_formats": [1, 200],
                "description": "broken"
              },
              "overlays": {"entries": [{"directory": "absent_overlay", "formats": [1, 5]}]}
            }
            """.trimIndent(),
        )
        wand(pack)
        write(pack, "assets/$NAMESPACE/items/wand.json", itemDefinition(-1, listOf("\"one\"", "\"one\"")))
        write(
            pack,
            "assets/$NAMESPACE/models/item/dangling.json",
            """
            {
              "parent": "$NAMESPACE:item/nope",
              "textures": {"layer0": "$NAMESPACE:item/nope", "layer1": "#layer0"}
            }
            """.trimIndent(),
        )
        write(pack, "assets/$NAMESPACE/models/item/loop_a.json", """{"parent": "$NAMESPACE:item/loop_b"}""")
        write(pack, "assets/$NAMESPACE/models/item/loop_b.json", """{"parent": "$NAMESPACE:item/loop_a"}""")
        write(
            pack,
            "assets/$NAMESPACE/models/item/legacy.json",
            """
            {
              "parent": "minecraft:item/handheld",
              "overrides": [{"predicate": {"custom_model_data": 1}, "model": "$NAMESPACE:item/wand"}]
            }
            """.trimIndent(),
        )
        write(pack, "assets/$NAMESPACE/models/item/Upper.json", """{"parent": "minecraft:item/generated"}""")
        write(
            pack,
            "assets/$NAMESPACE/sounds.json",
            """{"$NAMESPACE.whoosh": {"sounds": [{"name": "$NAMESPACE:absent/whoosh"}]}}""",
        )
        write(
            pack,
            "assets/$NAMESPACE/font/broken.json",
            """
            {"providers": [{"type": "bitmap", "file": "$NAMESPACE:font/absent.png", "ascent": 8, "chars": ["a"]}]}
            """.trimIndent(),
        )
        write(
            pack,
            "assets/$NAMESPACE/atlases/blocks.json",
            """{"sources": [{"type": "minecraft:single", "resource": "$NAMESPACE:absent/sprite"}]}""",
        )
        png(pack, "assets/$NAMESPACE/textures/item/odd.png", 12, 12)
        png(pack, "assets/$NAMESPACE/textures/item/anim.png", 16, 24)
        write(pack, "assets/$NAMESPACE/textures/item/anim.png.mcmeta", """{"animation": {}}""")
        bytes(pack, "assets/$NAMESPACE/textures/item/fake.png", "this is not a png".toByteArray())
        bytes(pack, ".DS_Store", ByteArray(64))
        bytes(pack, "source/wand.psd", ByteArray(2048))
        return pack
    }

    /** The pack the author meant, one directory below where the client looks for it. */
    fun nestedRoot(root: Path): Path {
        val pack = root.resolve("nested")
        write(pack, "MyPack/pack.mcmeta", """{"pack": {"pack_format": $FORMAT}}""")
        write(pack, "MyPack/assets/$NAMESPACE/models/item/wand.json", """{"parent": "minecraft:item/generated"}""")
        return pack
    }

    /** A second pack that claims the same item definition as [clean]. */
    fun rival(root: Path): Path {
        val pack = root.resolve("rival")
        write(pack, "pack.mcmeta", """{"pack": {"pack_format": $FORMAT}}""")
        write(pack, "assets/$NAMESPACE/items/wand.json", itemDefinition(0, listOf("\"nine\"")))
        write(pack, "assets/$NAMESPACE/models/item/wand.json", """{"parent": "minecraft:item/generated"}""")
        return pack
    }

    fun zip(pack: Path, target: Path): Path {
        Files.createDirectories(target.parent)
        ZipOutputStream(Files.newOutputStream(target)).use { out ->
            Files.walk(pack).use { stream ->
                for (file in stream.filter { Files.isRegularFile(it) }.sorted()) {
                    out.putNextEntry(ZipEntry(pack.relativize(file).toString().replace('\\', '/')))
                    out.write(Files.readAllBytes(file))
                    out.closeEntry()
                }
            }
        }
        return target
    }

    /** The model and texture every fixture references, so a miss is always the fixture's own doing. */
    private fun wand(pack: Path) {
        write(
            pack,
            "assets/$NAMESPACE/models/item/wand.json",
            """
            {"parent": "minecraft:item/generated", "textures": {"layer0": "$NAMESPACE:item/wand"}}
            """.trimIndent(),
        )
        png(pack, "assets/$NAMESPACE/textures/item/wand.png", 16, 16)
    }

    /** @param whens raw JSON for each case's `when`, so both the single and the list form are covered. */
    private fun itemDefinition(index: Int, whens: List<String>): String {
        val model = """{"type": "minecraft:model", "model": "$NAMESPACE:item/wand"}"""
        val cases = whens.joinToString(", ") { """{"when": $it, "model": $model}""" }
        return """
            {
              "model": {
                "type": "minecraft:select",
                "property": "minecraft:custom_model_data",
                "index": $index,
                "fallback": $model,
                "cases": [$cases]
              }
            }
        """.trimIndent()
    }

    private fun write(pack: Path, path: String, text: String) = bytes(pack, path, text.toByteArray())

    private fun bytes(pack: Path, path: String, content: ByteArray) {
        val file = pack.resolve(path)
        Files.createDirectories(file.parent)
        Files.write(file, content)
    }

    private fun png(pack: Path, path: String, width: Int, height: Int) {
        val file = pack.resolve(path)
        Files.createDirectories(file.parent)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        Files.newOutputStream(file).use { out ->
            require(ImageIO.write(image, "png", out)) { "no PNG writer available" }
        }
    }
}
