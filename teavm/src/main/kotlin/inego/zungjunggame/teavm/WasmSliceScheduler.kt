package inego.zungjunggame.teavm

import com.github.xpenatan.gdx.teavm.backends.web.BrowserFiber
import org.teavm.jso.JSObject

/**
 * Cooperative macro-task slice driver for Web / WasmGC via [NativeScheduler]'s
 * `MessageChannel` bridge. Each tick hops into Wasm through [BrowserFiber.enter]
 * so the JS export itself stays a plain method (never a TeaVM coroutine).
 */
object WasmSliceScheduler {
    fun startLoop(step: () -> Boolean): () -> Unit {
        var cancelled = false
        var handle: JSObject? = null
        val onTick = NativeScheduler.Callback {
            BrowserFiber.enter {
                if (cancelled) return@enter
                val more = try {
                    step()
                } catch (e: Throwable) {
                    cancelled = true
                    handle?.let { NativeScheduler.closeSliceChannel(it) }
                    throw e
                }
                if (more && !cancelled) {
                    handle?.let { NativeScheduler.scheduleSlice(it) }
                } else {
                    cancelled = true
                    handle?.let { NativeScheduler.closeSliceChannel(it) }
                }
            }
        }
        val ch = NativeScheduler.createSliceChannel(onTick)
        handle = ch
        NativeScheduler.scheduleSlice(ch)
        return {
            if (!cancelled) {
                cancelled = true
                NativeScheduler.closeSliceChannel(ch)
            }
        }
    }
}
