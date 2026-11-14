**Mahjong Tile Shuffle – Brief 2D Physics Model Spec**

### Goal
Simulate 136 rectangular mahjong tiles on a square table with hard borders. Tiles remain strictly 2D (no lifting or flipping). Players may each use 0/1/2 hands to push tiles during the shuffle phase. After agitation, tiles settle under friction.

### Fixed physical scale (tutorial shuffle)
World units for the wall-build shuffle are **millimetres**, not diagram pixels:

| Quantity | Value |
|----------|-------|
| Table square side | **800 mm** |
| Tile long side | **40 mm** |
| Tile short side | 40 × diagram aspect (≈ 28.8 mm) |

Ratio table/tile = 20 is fixed. The diagram square is only a view transform
(`pxPerMm = squareSidePx / 800`); resizing the UI remounts the map, not the sim.

Face-down layout (before shuffle) uses the same scale: tiles are drawn at
`40/800` of the square side, not fit-to-fill, so entering the physics shuffle
does not resize the set.

### Core Entities

**Tile** (×136)
- State: center \(\mathbf{r}=(x,y)\), angle \(\theta\), linear velocity \(\mathbf{v}\), angular velocity \(\omega\)
- Constants: mass \(m\), size \(L\times W\), inertia \(I=m(L^2+W^2)/12\)
- Dynamics (no contact):
  \[
  m\dot{\mathbf{v}}=\mathbf{F}_\text{fric}+\mathbf{F}_\text{ext},\quad
  I\dot{\omega}=\tau_\text{fric}+\tau_\text{ext}
  \]

**Table**
- Square hard borders (infinite-mass walls).
- Uniform surface friction under every tile.

**Hand** (0–8 total, 0–2 per player)
- State: position \(\mathbf{h}\), influence radius \(R\), force vector \(\mathbf{F}_h\) (or desired velocity)
- Lifetime / on-off controlled by player or AI.

### Surface Friction (felt with inertia)
Free tiles keep a **short** coast after a shove or grip release — real mass —
but must not glide like liquid/ice or fly off like light chips.

- Linear Coulomb stop: reduce \(|\mathbf{v}|\) by \(\max(\mu_k g,\,s L)\,dt\) (default \(s\sim 26\)–\(30\)), snap to 0 below epsilon
- Angular: Coulomb with lever \(r_\text{spin}\sim 0.28L\), plus viscous \(\tau=-c_\omega\omega\) (\(c_\omega\propto I\))
- Grip release keeps a modest fraction of palm velocity (`releaseVelocityKeep` ~0.4–0.5)
- Sleep after a brief still period
- **Kinematic grip push is soft**: max normal Δv per free–grip contact / substep
  (`kinematicMaxDeltaVScale` · L) so infinite-mass hands do not slingshot tiles
- Free linear speed hard-capped at `freeMaxSpeedScale` · L/s

### Collision Detection
- Tile–tile: OBB via Separating Axis Theorem → contact normal \(\mathbf{n}\), penetration depth, contact point.
- Tile–wall: axis-aligned interval tests against the four borders.

### Collision Response
Sequential impulse solver (5–15 iterations per step).  
Normal impulse (restitution \(e\)):
\[
j_n=-\frac{(1+e)(\mathbf{v}_\text{rel}\cdot\mathbf{n})}{\frac1{m_1}+\frac1{m_2}+\frac{(\mathbf{r}_1\times\mathbf{n})^2}{I_1}+\frac{(\mathbf{r}_2\times\mathbf{n})^2}{I_2}}
\]
(wall terms → 0).  
Tangential friction impulse clamped by Coulomb cone \(\lvert j_t\rvert\le\mu j_n\).  
Baumgarte + soft positional nudge for residual penetration, then **one unified
geometric resolve**: up to N re-detect → free-body separate → hard table-bound
passes (early-out under residual slop). Bounds run *inside* the loop so rim
clamps that re-introduce free–free / free–grip overlap are cleaned by the next
pass — no second full separation phase. Soft velocity response stays heavy;
geometry stays rigid like real tiles.

### Hand Interaction (simple sequential mash)
One hand at a time. Free tiles stay passive until contacted.

1. **Pick**: seed = free tile **farthest from the table square center**;
   grip that seed plus free tiles within a **2 tile-length** radius (closest
   first), **up to 7** total.
2. **Hold**: palm at cluster centroid; lock each tile’s offset and angle
   (kinematic / infinite mass). Internal contacts in the same grip are skipped.
3. **Push**: **ease-in-out** shove toward a **jittered point near the table
   square center** (uniform disk, radius `strokeAimJitterScale` · L ≈ 3 L), and
   **slightly past** it (`strokePastComScale` · L). Duration is travel / avg
   speed (~9 L/s), clamped ~0.22–0.55 s — eased, not a linear crawl and not a
   violent fling. Gripped tiles shove free neighbors via contacts. If the palm
   is already on the aim point, pick a random unit direction.
4. **Release**: clear the grip; tiles keep a fraction of palm velocity
   (`releaseVelocityKeep`); driver keeps a mild residual floor so release is not zero.
5. **Next**: immediately start another stroke (always ≤1 active hand).

Hands are **not** soft radial force fields (that reads as flocking). They are
not extra rigid bodies — only kinematic drivers for the gripped set.

Tutorial driver (`WallBuildView`): sim in mm (800 / 40); single sequential
hand as above.

### Simulation Phases
1. **Setup**: place 136 non-overlapping tiles (face-down layout or packed grid).
2. **Agitation**: continuous one-hand strokes (rim-outlier cluster → near square center + past).
3. **Settling** (optional): deactivate hands; friction + inelastic collisions until rest.
4. Optional final non-penetration cleanup.

### Suggested Default Parameters
- Time step: 1/120 s or smaller
- Restitution \(e\): ~0.04–0.1 (mostly inelastic; 0 on grip hits)
- Friction \(\mu\): 0.5–0.65 (tile–tile and tile–wall)
- Surface \(\mu_k\): ~0.75–0.95; stop floor ~26–30 L/s²
- Kinematic max Δv: ~1.1 L per contact/substep; free max speed ~6–7 L/s
- Grip: farthest-from-square-center seed + up to 7 tiles in 2 L radius
- Stroke aim: near table square center (jitter disk ~3 L); past by ~0.7 L; min length ~1.5 L
- Stroke motion: ease-in-out cubic; avg ~9 L/s (peak ~3×); duration 0.22–0.55 s

### Performance Notes
136 bodies is fine for a 2D OBB mash **if** the *schedule* is budgeted. Cost is
dominated by hard geometric resolve (each pass rebuilds the contact set), not
by tile count:

```
≈ maxSubSteps × (solverIters + hardSepIters × detect)
```

Material feel (μ, mass, stroke shape) is shared; computational budget is
`ShuffleSolverSchedule`:

| Schedule | fixedDt | maxSub | hardSep | step budget | Target |
|----------|---------|--------|---------|-------------|--------|
| **High** | 1/120 s | 4 | 10 | unlimited | desktop |
| **Interactive** | 1/60 s | 2 | 2 | 10 ms | mobile 60 Hz |

`WallShuffleDriver` picks Interactive on Android/iOS and High elsewhere.
`TileShuffleSim.step` drains the time accumulator if a budget cut fires so lag
cannot spiral. Contact records and broad-phase cell lists are pooled (no
per-detect heap traffic). Sleep inactive tiles after settling.

### Implementation

Custom sequential-impulse solver in `inego.zungjunggame.shuffle`:

- `TileShuffleSim` — world step, pooled contacts, grip hands, sleep, time budget
- `ShuffleSimConfig` — material / world params + `wallBuildTutorial(…)` factory
- `ShuffleSolverSchedule` — High / Interactive computational budgets
- `ShuffleTileBody` / `ShuffleHand` — bodies and kinematic palm grips
