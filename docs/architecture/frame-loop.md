# The frame loop

Part of the [implementation](implementation.md) map.

```
assemble → motion(dt) → layout          // inertia into the store; hit-test geometry
→ input                                 // intents; relayout when router.stack identity changes
→ host ticks                            // settlement, connect card, labels — state, not widget writes
→ assemble + layout                     // always, for paint
→ paint
→ post-paint host ticks                 // AI steps, local + online present, sound cues
→ flush-if-dirty
```

- **Paint range.** The topmost `Opaque` entry and everything above it. One function feeds
  layout, paint, input, and the scroll router. Stack membership is visibility.
- **Input layouts** exist so a coasting control and a same-frame tap agree. Relayout inside
  the input loop when `router.stack` identity changes (the intent that pushed also mutated
  hosts).
- **Paint layouts** are always from a model assembled **after** input and host ticks. A
  director advance this frame is in this frame's paint snapshot.
- **`motion(dt)` runs once**, before input, on the caller-assembled model. A lesson change
  that happens only inside a host tick is visible in this frame's paint and reconciled on
  the next frame's motion.
- **Restore and live are the same path:** the current stack, store, and model. The only
  difference is the widget's first-apply flag.
- **Text focus** is a `Layout` fact; the runtime opens or closes the platform keyboard from
  it.

`boardOrigin` is consumed once per `renderFrame` and passed to every assemble that frame.
