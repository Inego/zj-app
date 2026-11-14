package com.github.xpenatan.gdx.teavm.backends.web;

/**
 * WebGL graphics that honor continuous rendering.
 * <p>
 * The stock {@link WebGraphics} reports continuous rendering as always on and
 * ignores {@link #requestRendering()}. The application loop in {@link WebApplication}
 * reads these so an idle page stops scheduling animation frames.
 */
public class PacedWebGraphics extends WebGLGraphics {

    private final WebApplication app;
    private boolean continuous = true;

    public PacedWebGraphics(WebApplicationConfiguration config, WebApplication app) {
        super(config);
        this.app = app;
    }

    @Override
    public void setContinuousRendering(boolean isContinuous) {
        continuous = isContinuous;
    }

    @Override
    public boolean isContinuousRendering() {
        return continuous;
    }

    @Override
    public void requestRendering() {
        app.wakeFrame();
    }
}
