package io.yamsergey.dta.present.network

/** D-024: what of a Request cell's host and path fits [room] (measured by [width]): the path stays readable. */
object RequestFit {
    /** The host shown (whole, or dropped when both do not fit) and the path shown (cut last, at its end with …). */
    fun fit(host: String?, path: String, room: Int, width: (String) -> Int): Pair<String, String> {
        val h = host.orEmpty()
        if (width(h + path) <= room) return h to path
        if (width(path) <= room) return "" to path
        var n = path.length
        while (n > 0 && width(path.substring(0, n) + "…") > room) n--
        return "" to if (n == 0) "" else path.substring(0, n) + "…"
    }
}
