package com.github.xpenatan.gdx.teavm.backends.web;

import org.teavm.runtime.Fiber;

/**
 * Hop from a browser callback into Wasm.
 *
 * <p>A method JavaScript invokes — a {@code JSFunctor}, an {@code EventListener},
 * {@code ReadyStateChangeHandler}, {@code AnimationFrameCallback} — must not
 * itself be a TeaVM coroutine. A coroutine export reads {@code Fiber.current()}
 * in its prologue, and after {@code main} returns that is null, so the callback
 * traps with {@code RuntimeError: dereferencing a null pointer}. {@link Fiber}
 * is excluded from async analysis, so a body that only calls {@link #enter}
 * stays a plain method. {@code work} then runs on a daemon fiber, where
 * {@code Fiber.current} is set.
 *
 * <p>{@code enter} must stay a single {@code Fiber.start} call. Invoking
 * {@code work} directly would make {@code enter} a coroutine, and every
 * browser export that uses it would trap again.
 */
public final class BrowserFiber {
    private BrowserFiber() {}

    public static void enter(Fiber.FiberRunner work) {
        Fiber.start(work, true);
    }
}
