package inego.zungjunggame.features.reference

import com.badlogic.gdx.graphics.Camera
import inego.zungjunggame.input.Intent
import inego.zungjunggame.persist.ReferenceNavCodec
import inego.zungjunggame.render.reference.ReferenceCatalog
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteActions
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.RouteUiState
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.StackState

/**
 * Registers the [Route.Reference] route (key `reference`) with the runtime.
 *
 * Only the [Route.Reference] form is registered: [RouteSpecs.specFor] already
 * gives it the browser's full-screen behavior (owns input, scrim, back-dismiss,
 * persisted), so a second `overlay:reference` entry would be a dead duplicate.
 * The browser overlay is composition-root owned and supplied as a provider.
 */
object ReferenceFeature {
    fun register(registry: FeatureRegistry, overlay: () -> ReferenceBrowserOverlay) {
        val route = Route.Reference(StackState.EMPTY)
        registry.register(
            routeKey = route.key,
            factory = { registered -> ReferenceBrowserComponent(registered, overlay) },
            spec = RouteSpecs.specFor(route),
            actions = ReferenceActions,
        )
    }

    /** Registers the reference browser with a host-owned camera. */
    fun register(
        registry: FeatureRegistry,
        overlay: () -> ReferenceBrowserOverlay,
        camera: () -> Camera,
        routeUi: RouteUiState = RouteUiState(),
        canGoBack: () -> Boolean = { false },
    ) {
        val route = Route.Reference(StackState.EMPTY)
        registry.register(
            routeKey = route.key,
            factory = { registered ->
                ReferenceBrowserComponent(
                    route = registered,
                    overlay = overlay,
                    camera = camera,
                    state = routeUi.port(registered.key, ReferenceNavCodec),
                    canGoBack = canGoBack,
                )
            },
            spec = RouteSpecs.specFor(route),
            actions = ReferenceActions,
        )
    }
}

/**
 * Maps the browser's controls to [Intent]s.
 *
 * The × / dismiss exits the browser via [Intent.Back]; history ← pops the
 * in-session stack via [Intent.ReferenceBack]; a TOC row deep-links its page.
 * An article hyperlink is resolved with [ReferenceCatalog.resolveHref] so a
 * page link maps to [Intent.OpenReference], a section link to
 * [Intent.OpenReferenceSection], and an external URL to
 * [Intent.OpenReferenceExternal]. A press on the browser body (including the
 * compact drawer scrim) is not an outside dismissal, so [outside] is ignored.
 */
object ReferenceActions : RouteActions {
    const val DISMISS = "reference.dismiss"
    const val HISTORY_BACK = "reference.historyBack"
    const val TOC_PAGE = "reference.tocPage"
    const val LINK = "reference.link"
    const val TAB_SECTION = "reference.tabSection"
    const val KEY_PAGE_ID = "pageId"
    const val KEY_HREF = "href"
    const val KEY_SECTION = "section"

    /**
     * Maps the control a completed press resolved to its action, or null when
     * the gesture was a scroll or landed on nothing actionable.
     */
    fun actionFor(result: ReferenceVerdict): ComponentAction? = when {
        result.close -> ComponentAction(DISMISS)
        result.historyBack -> ComponentAction(HISTORY_BACK)
        result.tocPageId != null ->
            ComponentAction(TOC_PAGE, mapOf(KEY_PAGE_ID to result.tocPageId))
        result.linkHref != null ->
            ComponentAction(LINK, mapOf(KEY_HREF to result.linkHref))
        result.section != null ->
            ComponentAction(TAB_SECTION, mapOf(KEY_SECTION to result.section.id))
        else -> null
    }

    override fun toIntent(route: Route, action: ComponentAction): Intent? = when (action.id) {
        DISMISS -> Intent.Back
        HISTORY_BACK -> Intent.ReferenceBack
        TOC_PAGE -> (action.payload[KEY_PAGE_ID] as? String)?.let { Intent.OpenReference(it) }
        LINK -> linkIntent(action.payload[KEY_HREF] as? String)
        TAB_SECTION ->
            (action.payload[KEY_SECTION] as? String)?.let { Intent.OpenReferenceSection(it) }
        else -> null
    }

    private fun linkIntent(href: String?): Intent? {
        if (href.isNullOrBlank()) return null
        return when (val target = ReferenceCatalog.resolveHref(href)) {
            is ReferenceCatalog.LinkTarget.Page -> Intent.OpenReference(target.pageId)
            is ReferenceCatalog.LinkTarget.SectionTarget ->
                Intent.OpenReferenceSection(target.section.id)
            is ReferenceCatalog.LinkTarget.External -> Intent.OpenReferenceExternal(target.url)
            null -> null
        }
    }

    override fun back(route: Route): Intent = Intent.DismissReference
}
