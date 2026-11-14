/*
 * Derived from gdx-teavm 1.6.2 (https://github.com/xpenatan/gdx-teavm),
 * licensed under the Apache License, Version 2.0. Modified for this project;
 * see NOTICE and third_party/gdx-teavm/.
 */
package com.github.xpenatan.gdx.teavm.backends.web;

import com.badlogic.gdx.AbstractInput;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.input.NativeInputConfiguration;
import com.badlogic.gdx.utils.IntMap;
import com.badlogic.gdx.utils.IntSet;
import com.badlogic.gdx.utils.TimeUtils;
import com.github.xpenatan.gdx.teavm.backends.web.dom.DocumentExt;
import com.github.xpenatan.gdx.teavm.backends.web.dom.ElementExt;
import com.github.xpenatan.gdx.teavm.backends.web.dom.HTMLElementExt;
import com.github.xpenatan.gdx.teavm.backends.web.utils.KeyCodes;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSProperty;
import org.teavm.jso.browser.Window;
import org.teavm.jso.core.JSArrayReader;
import org.teavm.jso.dom.events.Event;
import org.teavm.jso.dom.events.EventListener;
import org.teavm.jso.dom.events.EventTarget;
import org.teavm.jso.dom.events.KeyboardEvent;
import org.teavm.jso.dom.events.MouseEvent;
import org.teavm.jso.dom.events.Touch;
import org.teavm.jso.dom.events.TouchEvent;
import org.teavm.jso.dom.events.WheelEvent;
import org.teavm.jso.dom.html.*;
import org.teavm.jso.dom.xml.Element;

import java.util.Arrays;

/**
 * @author xpenatan
 */
public class WebInput extends AbstractInput implements EventListener<Event> {

    private static float getMouseWheelVelocity(double delta, int deltaMode) {
        if(deltaMode == 2) {
            return 0;
        }
        float velocity = 0;
        if(delta < 0) {
            velocity = -1;
        }
        else if(delta > 0) {
            velocity = 1f;
        }
        return velocity;
    }

    private final HTMLCanvasElement canvas;

    static final int MAX_TOUCHES = 20;
    boolean justTouched = false;
    private final IntMap<Integer> touchMap = new IntMap<>(20);
    private final boolean[] touched = new boolean[MAX_TOUCHES];
    private final int[] touchX = new int[MAX_TOUCHES];
    private final int[] touchY = new int[MAX_TOUCHES];
    private final int[] deltaX = new int[MAX_TOUCHES];
    private final int[] deltaY = new int[MAX_TOUCHES];
    final IntSet pressedButtons = new IntSet();
    final boolean[] justPressedButtons = new boolean[5];
    InputProcessor processor;
    long currentEventTimeStamp;
    boolean hasFocus = true;

    private final WebApplication application;

    public WebInput(WebApplication application, HTMLCanvasElement canvas) {
        this.application = application;
        this.canvas = canvas;
        hookEvents();
        KeyCodes.initLayoutLabels();
    }

    private void hookEvents() {
        HTMLDocument document = canvas.getOwnerDocument();
        document.addEventListener("mousedown", this, false);
        document.addEventListener("mouseup", this, false);
        document.addEventListener("mousemove", this, false);
        document.addEventListener("wheel", this, false);

        document.addEventListener("keydown", this, false);
        document.addEventListener("keyup", this, false);
        document.addEventListener("keypress", this, false);

        Window.current().addEventListener("blur", this, false);

        canvas.addEventListener("touchstart", this, true);
        canvas.addEventListener("touchmove", this, true);
        canvas.addEventListener("touchcancel", this, true);
        canvas.addEventListener("touchend", this, true);
    }

    @Override
    public void handleEvent(Event e) {
        // Browser callback. Only the hop; see BrowserFiber.
        BrowserFiber.enter(() -> dispatch(e));
    }

    private void dispatch(Event e) {
        try{
            if(application.getApplicationListener() != null) {
                // A settled page is not in an animation frame. Pointer and key
                // events are what wake the next present, including hover.
                if(Gdx.graphics != null) {
                    Gdx.graphics.requestRendering();
                }
                handleMouseEvents(e);
                handleKeyboardEvents(e);
            }
        }
        catch(Throwable t) {
            application.onError(t);
        }
    }

    private void handleMouseEvents(Event e) {
        String type = e.getType();
        switch (type) {
            case "mousedown" -> {
                // makes sure the game has keyboard focus (e.g. when embedded in iFrame on itch.io)
                Window.current().focus();

                MouseEvent mouseEvent = (MouseEvent) e;
                EventTarget target = e.getTarget();
                boolean equals = target == canvas;
                if (!equals || touched[0]) { // TODO needs check equals bug
                    float mouseX = getRelativeX(mouseEvent, canvas);
                    float mouseY = getRelativeY(mouseEvent, canvas);
                    if (mouseX < 0 || mouseX > Gdx.graphics.getWidth() || mouseY < 0 || mouseY > Gdx.graphics.getHeight()) {
                        hasFocus = false;
                    }
                    return;
                }
                hasFocus = true;
                this.justTouched = true;
                this.touched[0] = true;
                int button = KeyCodes.getButton(mouseEvent.getButton());
                this.pressedButtons.add(button);
                justPressedButtons[button] = true;
                this.deltaX[0] = 0;
                this.deltaY[0] = 0;
                if (isCursorCatched()) {
                    this.touchX[0] += (int) mouseEvent.getMovementX();
                    this.touchY[0] += (int) mouseEvent.getMovementY();
                } else {
                    int relativeX = getRelativeX(mouseEvent, canvas);
                    int relativeY = getRelativeY(mouseEvent, canvas);
                    this.touchX[0] = relativeX;
                    this.touchY[0] = relativeY;
                }
                this.currentEventTimeStamp = TimeUtils.nanoTime();
                if (processor != null)
                    processor.touchDown(touchX[0], touchY[0], 0, KeyCodes.getButton(mouseEvent.getButton()));

                e.preventDefault();
                e.stopPropagation();
            }
            case "mouseup" -> {
                MouseEvent mouseEvent = (MouseEvent) e;
                if (!touched[0]) return;
                this.pressedButtons.remove(KeyCodes.getButton(mouseEvent.getButton()));
                this.touched[0] = pressedButtons.size > 0;
                if (isCursorCatched()) {
                    setDelta(0, (int) mouseEvent.getMovementX(), (int) mouseEvent.getMovementY());
                    this.touchX[0] += (int) mouseEvent.getMovementX();
                    this.touchY[0] += (int) mouseEvent.getMovementY();
                } else {
                    setDelta(0, getRelativeX(mouseEvent, canvas) - touchX[0], getRelativeY(mouseEvent, canvas) - touchY[0]);
                    this.touchX[0] = getRelativeX(mouseEvent, canvas);
                    this.touchY[0] = getRelativeY(mouseEvent, canvas);
                }
                this.currentEventTimeStamp = TimeUtils.nanoTime();
                this.touched[0] = false;
                if (processor != null)
                    processor.touchUp(touchX[0], touchY[0], 0, KeyCodes.getButton(mouseEvent.getButton()));
            }
            case "mousemove" -> {
                MouseEvent mouseEvent = (MouseEvent) e;
                if (isCursorCatched()) {
                    setDelta(0, (int) mouseEvent.getMovementX(), (int) mouseEvent.getMovementY());
                    this.touchX[0] += (int) mouseEvent.getMovementX();
                    this.touchY[0] += (int) mouseEvent.getMovementY();
                } else {
                    int relativeX = getRelativeX(mouseEvent, canvas);
                    int relativeY = getRelativeY(mouseEvent, canvas);
                    setDelta(0, relativeX - touchX[0], relativeY - touchY[0]);
                    this.touchX[0] = relativeX;
                    this.touchY[0] = relativeY;
                }
                this.currentEventTimeStamp = TimeUtils.nanoTime();
                if (processor != null) {
                    if (touched[0])
                        processor.touchDragged(touchX[0], touchY[0], 0);
                    else
                        processor.mouseMoved(touchX[0], touchY[0]);
                }
            }
            case "wheel" -> {
                WheelEvent wheel = (WheelEvent) e;
                if (processor != null) {
                    int deltaMode = wheel.getDeltaMode();
                    // Both axes of one wheel sample reach the app. The vertical
                    // sign is the DOM's own (deltaY positive scrolls down, which
                    // is libGDX's amountY); the horizontal is flipped, because
                    // the DOM's deltaX is positive scrolling right while libGDX
                    // amountX is positive the other way, and the desktop and
                    // Android backends negate their platform's x offset too.
                    float wheelY = getMouseWheelVelocity(wheel.getDeltaY(), deltaMode);
                    float wheelX = -getMouseWheelVelocity(wheel.getDeltaX(), deltaMode);
                    processor.scrolled((int) wheelX, (int) wheelY);
                }
                this.currentEventTimeStamp = TimeUtils.nanoTime();
            }
            case "touchstart" -> {
                this.justTouched = true;
                TouchEvent touchEvent = (TouchEvent) e;
                JSArrayReader<Touch> touches = touchEvent.getChangedTouches();
                for (int i = 0, j = touches.getLength(); i < j; i++) {
                    Touch touch = touches.get(i);
                    int real = touch.getIdentifier();
                    int touchId;
                    touchMap.put(real, touchId = getAvailablePointer());
                    touched[touchId] = true;
                    touchX[touchId] = getRelativeX(touch, canvas);
                    touchY[touchId] = getRelativeY(touch, canvas);
                    deltaX[touchId] = 0;
                    deltaY[touchId] = 0;
                    if (processor != null) {
                        processor.touchDown(touchX[touchId], touchY[touchId], touchId, Buttons.LEFT);
                    }
                }
                this.currentEventTimeStamp = TimeUtils.nanoTime();
                e.preventDefault();
            }
        }
        if (type.equals("touchmove")) {
            TouchEvent touchEvent = (TouchEvent) e;
            JSArrayReader<Touch> touches = touchEvent.getChangedTouches();
            for (int i = 0, j = touches.getLength(); i < j; i++) {
                Touch touch = touches.get(i);
                int real = touch.getIdentifier();
                int touchId = touchMap.get(real);
                setDelta(touchId, getRelativeX(touch, canvas) - touchX[touchId], getRelativeY(touch, canvas) - touchY[touchId]);
                touchX[touchId] = getRelativeX(touch, canvas);
                touchY[touchId] = getRelativeY(touch, canvas);
                if (processor != null) {
                    processor.touchDragged(touchX[touchId], touchY[touchId], touchId);
                }
            }
            this.currentEventTimeStamp = TimeUtils.nanoTime();
            e.preventDefault();
        }
        if(type.equals("touchcancel")) {
            TouchEvent touchEvent = (TouchEvent)e;
            JSArrayReader<Touch> touches = touchEvent.getChangedTouches();
            for(int i = 0, j = touches.getLength(); i < j; i++) {
                Touch touch = touches.get(i);
                int real = touch.getIdentifier();
                int touchId = touchMap.get(real);
                touchMap.remove(real);
                touched[touchId] = false;
                int relativeX = getRelativeX(touch, canvas);
                int relativeY = getRelativeY(touch, canvas);
                setDelta(touchId, relativeX - touchX[touchId], relativeY - touchY[touchId]);
                touchX[touchId] = relativeX;
                touchY[touchId] = relativeY;
                if(processor != null) {
                    processor.touchUp(touchX[touchId], touchY[touchId], touchId, Buttons.LEFT);
                }
            }
            this.currentEventTimeStamp = TimeUtils.nanoTime();
            e.preventDefault();
        }
        if(type.equals("touchend")) {
            TouchEvent touchEvent = (TouchEvent)e;
            JSArrayReader<Touch> touches = touchEvent.getChangedTouches();
            for(int i = 0, j = touches.getLength(); i < j; i++) {
                Touch touch = touches.get(i);
                int real = touch.getIdentifier();
                int touchId = touchMap.get(real);
                touchMap.remove(real);
                touched[touchId] = false;
                int relativeX = getRelativeX(touch, canvas);
                int relativeY = getRelativeY(touch, canvas);
                setDelta(touchId, relativeX - touchX[touchId], relativeY - touchY[touchId]);
                touchX[touchId] = relativeX;
                touchY[touchId] = relativeY;
                if(processor != null) {
                    processor.touchUp(touchX[touchId], touchY[touchId], touchId, Buttons.LEFT);
                }
            }
            this.currentEventTimeStamp = TimeUtils.nanoTime();
            e.preventDefault();
        }
    }

    /**
     * Resolves a KeyboardEvent to a libGDX {@link com.badlogic.gdx.Input.Keys} constant.
     * Prefers KeyboardEvent.code (physical key position, layout-independent) and
     * falls back to the deprecated KeyboardEvent.keyCode for older browsers.
     */
    private static int resolveKeyCode(KeyboardEvent keyboardEvent) {
        // Prefer event.code (physical key location, works on all keyboard layouts)
        String codeStr = keyboardEvent.getCode();
        if (codeStr != null && !codeStr.isEmpty()) {
            int resolved = KeyCodes.keyForCode(codeStr);
            if (resolved != Keys.UNKNOWN) {
                return resolved;
            }
        }
        // Fallback to deprecated event.keyCode for unrecognized or missing codes
        return KeyCodes.keyForCode(keyboardEvent.getKeyCode());
    }

    private void handleKeyboardEvents(Event e) {
        String type = e.getType();

        if(type.equals("keydown") && hasFocus) {
            KeyboardEvent keyboardEvent = (KeyboardEvent)e;
            int code = resolveKeyCode(keyboardEvent);

            // Capture the layout-specific label (event.key) for this physical key.
            // Single-character keys get their label stored so applications can display
            // the user's actual key cap rather than the QWERTY positional name.
            String key = keyboardEvent.getKey();
            if (code != Keys.UNKNOWN && key != null && key.length() == 1) {
                KeyCodes.setLayoutLabel(code, key.toUpperCase());
            }

            char keyChar = switch (code) {
                case Keys.DEL -> 8;
                case Keys.FORWARD_DEL -> 127;
                default -> 0;
            };

            if(!pressedKeys[code]) {
                pressedKeyCount++;
                pressedKeys[code] = true;
                keyJustPressed = true;
                justPressedKeys[code] = true;
            }

            if(isCatchKey(code) || code == Keys.LEFT || code == Keys.RIGHT || code == Keys.UP || code == Keys.DOWN) {
                e.preventDefault();
            }

            if(code == Input.Keys.DEL || code == Keys.FORWARD_DEL) {
                e.preventDefault();
                if(processor != null) {
                    processor.keyDown(code);
                    processor.keyTyped(keyChar);
                }
            }
            else {
                if(processor != null) {
                    processor.keyDown(code);
                }
            }

            // prevent TAB-key propagation, i.e. we handle ourselves!
            if(code == Keys.TAB) {
                e.preventDefault();
                e.stopPropagation();
            }
        }
        else if(type.equals("keypress") && hasFocus) {
            KeyboardEvent keyboardEvent = (KeyboardEvent)e;
            char c = (char)keyboardEvent.getCharCode();
            if(processor != null) processor.keyTyped(c);

            // prevent TAB-key propagation, i.e. we handle ourselves!
            if(c == '\t') {
                e.preventDefault();
                e.stopPropagation();
            }
        }
        else if(type.equals("blur")) {
            // The keyup for keys held while focus moves away (DevTools, browser shortcuts,
            // switching windows) is delivered elsewhere, which would leave those keys reported
            // as pressed forever. Mirror GLFW, which generates synthetic key releases when a
            // window loses input focus.
            for(int code = 0; code < pressedKeys.length; code++) {
                if(pressedKeys[code]) {
                    pressedKeyCount--;
                    pressedKeys[code] = false;
                    if(processor != null) {
                        processor.keyUp(code);
                    }
                }
            }
        }
        else if(type.equals("keyup") && hasFocus) {
            KeyboardEvent keyboardEvent = (KeyboardEvent)e;
            int code = resolveKeyCode(keyboardEvent);

            if(isCatchKey(code)) {
                e.preventDefault();
            }

            if(pressedKeys[code]) {
                pressedKeyCount--;
                pressedKeys[code] = false;
            }
            if(processor != null) {
                processor.keyUp(code);
            }

            // prevent TAB-key propagation, i.e. we handle ourselves!
            if(code == Keys.TAB) {
                e.preventDefault();
                e.stopPropagation();
            }
        }
    }

    private int getAvailablePointer() {
        for(int i = 0; i < MAX_TOUCHES; i++) {
            if(!touchMap.containsValue(i, false)) return i;
        }
        return -1;
    }

    public void reset() {
        if(justTouched) {
            justTouched = false;
            Arrays.fill(justPressedButtons, false);
        }

        if(keyJustPressed) {
            keyJustPressed = false;
            Arrays.fill(justPressedKeys, false);
        }
        for(int i = 0; i < touchX.length; i++) {
            deltaX[i] = 0;
            deltaY[i] = 0;
        }
    }

    public void setDelta(int touchId, int x, int y) {
        deltaX[touchId] = x;
        deltaY[touchId] = y;
    }

    private Element getCompatMode(HTMLDocument target) {
        DocumentExt doc = (DocumentExt) target;
        String compatMode = doc.getCompatMode();
        boolean isComp = compatMode.equals("CSS1Compat");
        // TODO This may not work
        return isComp ? target.getDocumentElement() : (Element)target;
    }

    private int getScrollTop(Element target) {
        ElementExt elem = (ElementExt)target;
        int val = elem.getScrollTop();
        return toInt32(val);
    }

    private int getScrollTop(HTMLDocument target) {
        Element element = getCompatMode(target);
        return getScrollTop(element);
    }

    private int getScrollLeft(Element target) {
        ElementExt elem = (ElementExt)target;
        int val = elem.getScrollLeft();
        return toInt32(val);
    }

    private int getScrollLeft(HTMLDocument target) {
        Element element = getCompatMode(target);
        return getScrollLeft(element);
    }

    private int getRelativeX(HTMLCanvasElement target, Touch touch) {
        return (int)(touch.getClientX() - getAbsoluteLeft(target) + getScrollLeft(target)
                        + getScrollLeft(target.getOwnerDocument()));
    }

    private int getRelativeY(HTMLCanvasElement target, Touch touch) {
        return (int)(touch.getClientY() - getAbsoluteTop(target) + getScrollTop(target)
                        + getScrollTop(target.getOwnerDocument()));
    }

    protected int getRelativeX(MouseEvent e, HTMLCanvasElement target) {
        float xScaleRatio = target.getWidth() * 1f / getClientWidth(target); // Correct for canvas CSS scaling
        return Math.round(xScaleRatio
                * (e.getClientX() - getAbsoluteLeft(target) + getScrollLeft(target) + getScrollLeft(target.getOwnerDocument())));
    }

    protected int getRelativeY(MouseEvent e, HTMLCanvasElement target) {
        float yScaleRatio = target.getHeight() * 1f / getClientHeight(target); // Correct for canvas CSS scaling
        return Math.round(yScaleRatio
                * (e.getClientY() - getAbsoluteTop(target) + getScrollTop(target) + getScrollTop(target.getOwnerDocument())));
    }

    protected int getRelativeX(Touch touch, HTMLCanvasElement target) {
        float xScaleRatio = target.getWidth() * 1f / getClientWidth(target); // Correct for canvas CSS scaling
        return Math.round(xScaleRatio * getRelativeX(target, touch));
    }

    protected int getRelativeY(Touch touch, HTMLCanvasElement target) {
        float yScaleRatio = target.getHeight() * 1f / getClientHeight(target); // Correct for canvas CSS scaling
        return Math.round(yScaleRatio * getRelativeY(target, touch));
    }

    private int  getClientWidth(HTMLCanvasElement target) {
        return target.getClientWidth();
    }

    private int getClientHeight(HTMLCanvasElement target) {
        return target.getClientHeight();
    }

    private int getAbsoluteTop(HTMLCanvasElement target) {
        return toInt32(getSubPixelAbsoluteTop(target));
    }

    private double getSubPixelAbsoluteTop(HTMLElement elem) {
        float top = 0;
        HTMLElementExt curr = (HTMLElementExt)elem;
        while(curr.getOffsetParent() != null) {
            top -= curr.getScrollTop();
            curr = (HTMLElementExt)curr.getParentNode();
        }

        while(elem != null) {
            top += elem.getOffsetTop();
            elem = curr.getOffsetParent();
        }

        return top;
    }

    private int getAbsoluteLeft(HTMLCanvasElement target) {
        return toInt32(getSubPixelAbsoluteLeft(target));
    }

    private double getSubPixelAbsoluteLeft(HTMLElement elem) {
        float left = 0;

        HTMLElementExt curr = (HTMLElementExt)elem;

        while(curr.getOffsetParent() != null) {
            left -= curr.getScrollLeft();
            curr = (HTMLElementExt)curr.getParentNode();
        }

        while(elem != null) {
            left += elem.getOffsetLeft();
            elem = curr.getOffsetParent();
        }
        return left;
    }

    private static int toInt32(double val) {
        return (int)val;
    }

    @Override
    public float getAccelerometerX() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public float getAccelerometerY() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public float getAccelerometerZ() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public float getGyroscopeX() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public float getGyroscopeY() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public float getGyroscopeZ() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int getMaxPointers() {
        return MAX_TOUCHES;
    }

    @Override
    public int getX() {
        return touchX[0];
    }

    @Override
    public int getX(int pointer) {
        return touchX[pointer];
    }

    @Override
    public int getDeltaX() {
        return deltaX[0];
    }

    @Override
    public int getDeltaX(int pointer) {
        return deltaX[pointer];
    }

    @Override
    public int getY() {
        return touchY[0];
    }

    @Override
    public int getY(int pointer) {
        return touchY[pointer];
    }

    @Override
    public int getDeltaY() {
        return deltaY[0];
    }

    @Override
    public int getDeltaY(int pointer) {
        return deltaY[pointer];
    }

    @Override
    public boolean isTouched() {
        for(int pointer = 0; pointer < MAX_TOUCHES; pointer++) {
            if(touched[pointer]) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean justTouched() {
        return justTouched;
    }

    @Override
    public boolean isTouched(int pointer) {
        return touched[pointer];
    }

    @Override
    public float getPressure() {
        return getPressure(0);
    }

    @Override
    public float getPressure(int pointer) {
        return isTouched(pointer) ? 1 : 0;
    }

    @Override
    public boolean isButtonPressed(int button) {
        return pressedButtons.contains(button) && touched[0];
    }

    @Override
    public boolean isButtonJustPressed(int button) {
        if(button < 0 || button >= justPressedButtons.length) return false;
        return justPressedButtons[button];
    }

    @Override
    public void getTextInput(TextInputListener listener, String title, String text, String hint) {
        this.getTextInput(listener, title, text, hint, OnscreenKeyboardType.Default);
    }

    @Override
    public void setOnscreenKeyboardVisible(boolean visible) {
        // TODO Auto-generated method stub

    }

    @Override
    public void vibrate(int milliseconds) {
        if (isVibrationSupportedCatchedJSNI() && milliseconds > 0)
            vibrateNativeCatchedJSNI(milliseconds);
    }

    @Override
    public void vibrate(int milliseconds, boolean fallback) {
        vibrate(milliseconds);
    }

    @Override
    public void vibrate(int milliseconds, int amplitude, boolean fallback) {
        vibrate(milliseconds);
    }

    @Override
    public void vibrate(VibrationType vibrationType) {
        int duration = switch (vibrationType) {
            case LIGHT -> 50;
            case MEDIUM -> 100;
            case HEAVY -> 500;
        };
        vibrate(duration);
    }

    @Override
    public float getAzimuth() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public float getPitch() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public float getRoll() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public void getRotationMatrix(float[] matrix) {
        // TODO Auto-generated method stub

    }

    @Override
    public long getCurrentEventTime() {
        return currentEventTimeStamp;
    }

    @Override
    public void setInputProcessor(InputProcessor processor) {
        this.processor = processor;
    }

    @Override
    public InputProcessor getInputProcessor() {
        return processor;
    }

    @Override
    public boolean isPeripheralAvailable(Peripheral peripheral) {
        if(peripheral == Peripheral.Accelerometer)
            return false;
        else if(peripheral == Peripheral.Compass)
            return false;
        else if(peripheral == Peripheral.HardwareKeyboard)
            return true;
//		else if (peripheral == Peripheral.MultitouchScreen)
//			return isTouchScreen();
        else if(peripheral == Peripheral.OnscreenKeyboard)
            return false;
        else if(peripheral == Peripheral.Vibrator)
            return false;
        return false;
    }

    @Override
    public int getRotation() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public Orientation getNativeOrientation() {
        return Orientation.Landscape;
    }

    @Override
    public void setCursorCatched(boolean catched) {
        if(catched) {
            setCursorCatchedJSNI(canvas);
        }
        else {
            exitCursorCatchedJSNI();
        }
    }

    @Override
    public boolean isCursorCatched() {
        return isCursorCatchedJSNI(canvas);
    }

    @Override
    public void setCursorPosition(int x, int y) {
        // not possible in a browser window
    }

    @Override
    public void getTextInput(TextInputListener listener, String title, String text, String hint, OnscreenKeyboardType type) {
        HTMLDocument document = Window.current().getDocument();
        HTMLElement oldTextInput = document.getElementById("text_input");
        if (null != oldTextInput) { // remove old dialog
            document.getBody().removeChild(oldTextInput);
        }
        HTMLElement overlay = document.createElement("div");
        overlay.setId("text_input");
        Runnable close = () -> document.getBody().removeChild(overlay);
        overlay.getStyle().setCssText("position: fixed;" +
                "top: 0;" +
                "left: 0;" +
                "width: 100%;" +
                "height: 100%;" +
                "background: rgba(0,0,0,0.5);" +
                "display: flex;" +
                "align-items: center;" +
                "justify-content: center;" +
                "z-index: 9999;");
        HTMLElement dialog = document.createElement("div");
        dialog.getStyle().setCssText("background: white;" +
                "border-radius: 8px;" +
                "padding: 20px;" +
                "min-width: 300px;" +
                "max-width: 500px;" +
                "box-shadow: 0 10px 30px rgba(0,0,0,0.3);");
        HTMLElement titleEl = document.createElement("h3");
        titleEl.setTextContent(title);
        titleEl.getStyle().setCssText("margin: 0 0 10px 0;" +
                "color: #333;" +
                "font-size: 18px;");
        HTMLElement textEl = document.createElement("p");
        textEl.setTextContent(text);
        textEl.getStyle().setCssText("margin: 0 0 15px 0;" +
                "color: #666;" +
                "font-size: 14px;");
        HTMLElement buttonContainer = document.createElement("div");
        buttonContainer.getStyle().setCssText("display: flex;" +
                "justify-content: flex-end;" +
                "gap: 10px;");
        HTMLButtonElement cancelBtn = (HTMLButtonElement) document.createElement("button");
        cancelBtn.setTextContent("Cancel");
        cancelBtn.getStyle().setCssText("padding: 8px 16px;" +
                "border: 1px solid #ddd;" +
                "background: white;" +
                "border-radius: 4px;" +
                "cursor: pointer;" +
                "font-size: 14px;");
        HTMLButtonElement confirmBtn = (HTMLButtonElement) document.createElement("button");
        confirmBtn.setTextContent("OK");
        confirmBtn.getStyle().setCssText("padding: 8px 16px;" +
                "border: none;" +
                "background: #1890ff;" +
                "color: white;" +
                "border-radius: 4px;" +
                "cursor: pointer;" +
                "font-size: 14px;");
        dialog.appendChild(titleEl);
        dialog.appendChild(textEl);
        overlay.appendChild(dialog);
        cancelBtn.addEventListener("click", (EventListener<MouseEvent>) evt -> BrowserFiber.enter(close::run));
        buttonContainer.appendChild(cancelBtn);
        buttonContainer.appendChild(confirmBtn);
        document.getBody().appendChild(overlay);
        overlay.addEventListener("click", (EventListener<MouseEvent>) evt -> BrowserFiber.enter(() -> {
            if (evt.getTarget() == overlay) {
                if (null != listener)
                    listener.canceled();
                close.run();
            }
        }));
        HTMLElement inputContainer = document.createElement("div");
        inputContainer.getStyle().setCssText("position: relative;" +
                "margin-bottom: 20px;");
        HTMLInputElement input = (HTMLInputElement) document.createElement("input");
        input.setType("text");
        switch (type) {
            case NumberPad: {
                input.setType("number");
                break;
            }
            case Password: {
                input.setType("password");
                break;
            }
            default: {
                input.setType("text");
            }
        }
        // input.setValue(text);
        input.setPlaceholder(hint);
        input.getStyle().setCssText("width: 100%;" +
                "padding: 10px 35px 10px 10px;" +
                "border: 1px solid #ddd;" +
                "border-radius: 4px;" +
                "font-size: 14px;" +
                "box-sizing: border-box;");
        HTMLButtonElement clearBtn = (HTMLButtonElement) document.createElement("button");
        clearBtn.setInnerText("X");
        clearBtn.setType("button");
        clearBtn.getStyle().setCssText(
                "position: absolute;" +
                        "right: 8px;" +
                        "top: 50%;" +
                        "transform: translateY(-50%);" +
                        "background: none;" +
                        "border: none;" +
                        "font-size: 18px;" +
                        "cursor: pointer;" +
                        "color: #999;" +
                        "width: 20px;" +
                        "height: 20px;" +
                        "border-radius: 50%;" +
                        "display: block;" +
                        "align-items: center;" +
                        "justify-content: center;");
        inputContainer.appendChild(input);
        inputContainer.appendChild(clearBtn);
        dialog.appendChild(inputContainer);
        dialog.appendChild(buttonContainer);
        clearBtn.addEventListener("click", (EventListener<MouseEvent>) evt -> {
            input.setValue("");
            input.focus();
        });
        Runnable handleConfirm = () -> {
            String value = input.getValue();
            if (value.isEmpty()) {
                input.getStyle().setProperty("border-color", "red");
                input.getStyle().setProperty("animation", "shake 0.5s");
                Window.setTimeout(() -> {
                    input.getStyle().removeProperty("border-color");
                    input.getStyle().removeProperty("animation");
                }, 1000);
                return;
            }
            close.run();
            if (null != listener)
                listener.input(value);
        };
        overlay.addEventListener("keydown", (EventListener<KeyboardEvent>) evt -> BrowserFiber.enter(() -> {
            if ("Enter".equals(evt.getKey())) {
                handleConfirm.run();
            } else if ("Backspace".equals(evt.getKey())) {
                /*
                todo ...
                 I'm confused why the Backspace key has no effect in my custom text input dialog when running under TeaVM on the web.
                 The only way I can delete characters is by listening to keyboard events and manually removing the last character from the input value.
                */
                String value = input.getValue();
                if (value != null && !value.isEmpty()) {
                    InputWithSelection input1 = inputCastCatchedJSNI(input);
                    int start = input1.getSelectionStart();
                    int end = input1.getSelectionEnd();
                    if (start > 0) {
                        String newValue = value.substring(0, start - 1) + value.substring(end);
                        input1.setValue(newValue);
                        input1.setSelectionStart(start - 1);
                        input1.setSelectionEnd(end - 1 > -1 ? end - 1 : input1.getSelectionEnd());
                        evt.preventDefault();
                    }
                }
            } else if ("Escape".equals(evt.getKey())) {
                close.run();
                if (null != listener)
                    listener.canceled();
            }
        }));
        confirmBtn.addEventListener("click", (EventListener<MouseEvent>) evt -> BrowserFiber.enter(handleConfirm::run));
        Window.setTimeout(input::focus, 100);
    }

    @Override
    public void setOnscreenKeyboardVisible(boolean visible, OnscreenKeyboardType type) {
        // TODO Auto-generated method stub

    }

    @Override
    public void openTextInputField(NativeInputConfiguration nativeInputConfiguration) {

    }

    @Override
    public void closeTextInputField(boolean b) {

    }

    @Override
    public void setKeyboardHeightObserver(KeyboardHeightObserver keyboardHeightObserver) {

    }

    /**
     * from <a href="https://github.com/toji/game-shim/blob/master/game-shim.js">...</a>
     *
     * @param element Canvas
     */
    @JSBody(params = "element", script =
        """
            if (!element.requestPointerLock) {
               element.requestPointerLock = (function() {
                   return element.webkitRequestPointerLock || element.mozRequestPointerLock;\
               })();
            }
            element.requestPointerLock();"""
    )
    private static native void setCursorCatchedJSNI(HTMLElement element);

    /**
     * from <a href="https://github.com/toji/game-shim/blob/master/game-shim.js">...</a>
     */
    @JSBody(script =
            "document.exitPointerLock();"
    )
    private static native void exitCursorCatchedJSNI();

    /**
     * from <a href="https://github.com/toji/game-shim/blob/master/game-shim.js">...</a>
     *
     * @return is Cursor catched
     */
    @JSBody(params = "canvas", script =
        """
            if (document.pointerLockElement === canvas || document.mozPointerLockElement === canvas) {
               return true;
            }
            return false;"""
    )
    private static native boolean isCursorCatchedJSNI(HTMLElement canvas);

    public abstract static class InputWithSelection extends HTMLInputElement {
        @JSProperty
        public abstract int getSelectionStart();

        @JSProperty
        public abstract int getSelectionEnd();

        @JSProperty
        public abstract void setSelectionStart(int pos);

        @JSProperty
        public abstract void setSelectionEnd(int pos);
    }

    @JSBody(params = "el", script =
            "return el;"
    )
    public static native InputWithSelection inputCastCatchedJSNI(HTMLInputElement el);

    @JSBody(script =
            "return !!navigator.vibrate;"
    )
    private static native boolean isVibrationSupportedCatchedJSNI();

    @JSBody(params = "durationMillis", script =
            "navigator.vibrate(durationMillis);"
    )
    private static native void vibrateNativeCatchedJSNI(int durationMillis);

}
