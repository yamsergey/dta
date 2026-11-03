package io.yamsergey.dta.present.network

/**
 * What the person's own scrolling means for following the live list (feature 021): back at the
 * edge where new rows appear it follows again, away from it it stops. Rows arriving, reloads and
 * the view's own scrolling are not the person's and are never passed here.
 */
object Scrolling {
    /**
     * @param moved whether the person moved the view (its position changed)
     * @param atEdge whether the view now shows the edge where new rows appear
     */
    fun event(following: Boolean, moved: Boolean, atEdge: Boolean): LiveEvent? = when {
        !moved -> null
        atEdge && !following -> LiveEvent.JumpToEnd
        !atEdge && following -> LiveEvent.ScrolledAway
        else -> null
    }
}
