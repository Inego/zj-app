package inego.zungjunggame.teavm

import com.badlogic.gdx.utils.Disposable
import com.github.xpenatan.gdx.teavm.backends.web.WebGpuContextSignal
import inego.zungjunggame.platform.GpuContextPolicy

/**
 * The web's platform fact: its GL objects survive backgrounding, and a real loss
 * announces itself.
 *
 * Both halves are load-bearing, and neither is safe alone.
 *
 * **Durable.** We measured it, rather than assuming it: six
 * away/return cycles with the context-loss listener provably live — absences of
 * 5.9 s to 155 s — produced **zero** `contextlost` and **zero** `contextrestored`.
 * The listener being live is what makes that a measured negative instead of an
 * absence of evidence, and it is also what identified the reported ~1.2 s freeze as
 * *ours*: `Fonts.recoverAfterBackgrounding` against a 6–21 ms paint, plus
 * `TextureRecovery` re-uploading two owners to find nothing wrong.
 *
 * **Event-backed.** Durable is a claim that the common case needs no rebuild, and
 * a claim is only safe while the rare case is still covered. This platform's rare
 * case is a GPU driver reset or an OS reclaiming a backgrounded tab, and
 * [WebGpuContextSignal] is what reports it. Without it, installing durable would
 * trade a 1.2 s freeze for a silent black canvas — which is why Stage W treats the
 * two as one change rather than a flag flip.
 *
 * ### Why this is an object and not [inego.zungjunggame.platform.DurableGpuContexts]
 *
 * That default exists for a durable platform with *no* event of its own. The web
 * has one, and the difference is exactly the point: the durable default's
 * `onContextRecreated` returns an inert disposable, so installing it would install
 * durable durability with durable blindness.
 *
 * ### Threading
 *
 * The browser delivers `webglcontextrestored` to a [com.github.xpenatan.gdx.teavm.backends.web.BrowserFiber]
 * daemon fiber, not to the render loop's own callback. Those are not two threads —
 * a TeaVM fiber is cooperative and runs to completion or to a yield point, so it
 * cannot interleave with the inside of `renderFrame()`. What it *can* do is land
 * between two frames, which is precisely the shape this needs: the callback only
 * sets a pending flag, and the rebuild is spent by the next `consume()`.
 *
 * The same hop already carries `WasmPresenceScheduler`'s `visibilitychange`
 * handler, which mutates the online coordinator, so this is not a new assumption
 * about the scheduler.
 *
 * ### Scope, stated honestly
 *
 * A `contextrestored` asks the one recovery path to rebuild glyph atlases
 * ([inego.zungjunggame.render.fonts.Fonts]), the textures registered with
 * [inego.zungjunggame.render.assets.TextureRecovery], and the batch shader plus
 * mesh handles in [inego.zungjunggame.render.assets.ManagedGpuResources]. The
 * mesh half also drops the VAO attribute-location cache that libGDX 1.14.2's
 * `invalidate()` leaves populated — without that, the restored frame clears to
 * felt and every draw rasterizes nothing.
 */
class WasmGpuContexts : GpuContextPolicy {
    override val contextsAreDurable: Boolean = true

    /**
     * Forwards a real restore to the recovery path. Unlike the desktop default this
     * is not inert — see the class KDoc.
     *
     * A subscriber is expected to *request* recovery and nothing else; see
     * [WebGpuContextSignal.onContextRestored] on why rebuilding from here would
     * give up the one-rebuild-per-frame guarantee.
     */
    override fun onContextRecreated(onRecover: () -> Unit): Disposable {
        val handle = WebGpuContextSignal.onContextRestored(onRecover)
        return Disposable { handle.close() }
    }
}
