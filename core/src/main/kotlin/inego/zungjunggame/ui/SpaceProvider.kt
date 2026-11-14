package inego.zungjunggame.ui

/**
 * A [Component] whose layer publishes a [Layout.space] for the layers composed
 * into it, and therefore paints **around** them: its own pixels below and above
 * the bands of children the host hands it.
 *
 * The host owns iteration, band membership, and the transform; the provider owns
 * its pixels and their order. A provider never learns which routes its children
 * are — it paints [SpaceChildren.paint] for each band where its composition
 * needs them.
 */
interface SpaceProvider : Component {
    /**
     * Paints this layer and the space it provides: its own pixels around
     * `children.paint(band)` for each band, in order. `children.paint` runs
     * inside this layer's [Layout.space]; the provider paints its backdrop and
     * chrome outside it and may paint its own in-space pixels through the same
     * transform.
     */
    fun paintSpace(frame: Frame, layout: Layout, canvas: Canvas, children: SpaceChildren)

    /** A provider paints through [paintSpace]; the host never draws it directly. */
    override fun draw(frame: Frame, canvas: Canvas) = Unit
}

/** Paints a provider's `InSpace` children for one band, in stack order. */
fun interface SpaceChildren {
    fun paint(band: Int)
}
