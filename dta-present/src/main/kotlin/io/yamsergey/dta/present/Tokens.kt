package io.yamsergey.dta.present

/** A colour as 0xRRGGBB. */
@JvmInline
value class Rgb(val value: Int) {
    val hex: String get() = "#%06x".format(value)
}

/** A design token as a light/dark pair (tokens.json). */
data class DtaToken(val name: String, val light: Rgb, val dark: Rgb)

/**
 * The design system's colour tokens (tokens.json, version 1). Surfaces bind them to their own
 * toolkit: the IDE uses its own neutrals and these for DTA's semantic colours (surfaces.md).
 */
object Tokens {
    private val defined = mutableMapOf<String, DtaToken>()

    val all: List<DtaToken> = listOf(
        token("surface", 0xf6f7f8, 0x15171a),
        token("surface-raised", 0xffffff, 0x1d2024),
        token("surface-sunken", 0xeceef1, 0x101215),
        token("selection", 0xd9e6f6, 0x21324a),
        token("line", 0xdfe2e6, 0x2b2f35),
        token("line-strong", 0x7b8590, 0x6c7682),
        token("ink", 0x1a1d21, 0xe5e8eb),
        token("ink-muted", 0x4f5863, 0xa9b1ba),
        token("accent", 0x1d5bb8, 0x82b1f5),
        token("on-accent", 0xffffff, 0x0e1622),
        token("kind-http", 0x0c6a72, 0x5ec6cf),
        token("kind-ws", 0x5a46b0, 0xb0a2ff),
        token("node-view", 0x2d5b8a, 0x8db8e4),
        token("node-compose", 0x2e7346, 0x7fd39b),
        token("status-client", 0x8f5200, 0xf2b45a),
        token("status-server", 0xb4261c, 0xff8d82),
        token("caveat", 0x855000, 0xf0b34d),
        token("caveat-tint", 0xfdf1d8, 0x2d2310),
        token("inferred", 0x46566e, 0xa7b6cc),
        token("inferred-tint", 0xe8edf4, 0x1e2633),
        token("intervened", 0x9c1f69, 0xff86c8),
        token("intervened-tint", 0xfbe6f1, 0x34172b),
        alias("danger", "status-server"),
        token("danger-tint", 0xfde7e4, 0x381613),
        token("redacted", 0xd3d7dc, 0x3a3f46),
    )

    operator fun get(name: String): DtaToken? = defined[name]

    private fun token(name: String, light: Int, dark: Int) = DtaToken(name, Rgb(light), Rgb(dark)).also { defined[name] = it }

    private fun alias(name: String, of: String) = defined.getValue(of).copy(name = name).also { defined[name] = it }
}
