# The rating-history chart

Part of the [implementation](../implementation.md) map. The chart engine itself is in
`chart/`; this page covers how the feature owns and draws it.

## Ownership and requests

The chart's candles are one byte-bounded tile store and one cached draw list.
`net/RatingHistoryClient` owns the fetched tiles and the request scheduler; the route's
state is only whose history and which window, never a candle. `open(target)` fetches the
parameterless all-time answer — the one body that names the server-picked rung and the
bounds a viewport opens on — and the route's appearance is the trigger
(`app/RouteSurfaceLoad`, `AppOnlineEffects.ratingHistoryEnter`). `RatingHistoryComponent`
rebuilds a `ChartViewport` from `RatingHistoryState.window` every frame (or the
publication's all-time bounds when there is none), applies `ChartGestureRecognizer`'s
pan/zoom/wheel gestures through `ChartViewport.applying`, and writes the visible
`[from, to]` back through `RouteUiActions` — as `Double` epoch edges, never a
`Float` epoch. The route holds the window the player **asked for**; `ChartWindowEase` holds
the one being **painted**, so a zoom command (a control press, a wheel notch, All Time)
re-scales the axis over ~0.12 s instead of jumping, while a drag, a pinch or a coast snaps
the ease — a window under a finger is never behind it. A notch composes on the window the
player **asked for**, not on the one being painted: a stream of notches must add up to a
stream of zoom, and re-zooming a window that is still catching up would make the wheel
sluggish exactly when it is spun. The rung, the requests and the route
write read the asked-for window, so an animation never requests an intermediate frame or
persists one; only the paint and the popup read the eased one, and the Y scale eases toward
the asked-for window rather than toward each intermediate span. A gesture starts from the
painted window, so grabbing the chart mid-ease moves what the player can see. The plot is a
`Layout.gestureRegions` entry and the surface is `scrollable`, so
a chart drag is claimed before the layer's outside handling and both wheel axes reach it;
wheel X pans, wheel Y zooms anchored at the cursor. The anchor, and a drag's pixel scale, are
measured in the **painted candle area**, not in the plot: the plot's inset and rating gutter
are chrome, so an anchor measured across them holds an instant beside the pointer instead of
under it. The anchor holds that instant **except at a bound**: a window already on the
all-time start or end stays glued to it while the cursor (or a pinch midpoint) is within
`ChartViewport.EDGE_STICK_FRACTION` of that same edge. Holding the cursor's instant at the end
instead would walk the newest candles off the right edge a notch at a time, and leave "the
end" a pan away from where the player was zooming. The explicit zoom in/out and All Time
controls sit **outside the plot** — in the title band beside the name when the surface has room
(right-aligned before the close control), and in their own row below the title when it does
not — each its own claimed rect. The component applies them through the same `ChartGesture`
funnel and persists the result through the same route write. A zoom press is **one wheel
notch** at the chart's centre (`ChartGestureRecognizer.zoomUnits`), the same `Zoom` a sample
with the cursor at the same point emits, so a button and the mouse cannot ask for different
amounts at the same anchor; the wheel's remaining difference is that it is continuous (a trackpad
sends fractional units) while a press is a whole one. Zoom and All Time disable at their
clamps, and a disabled control still absorbs its press rather than leaking to the layer
below.

`requestCoverage(level, fromMs, toMs, immediate)` is called only when the visible tile run
or the rung changes, plus one immediate request on a settle, and never before the opening
all-time answer has established the publication whose rung the view uses and whose bounds
clamp a restored window — a pre-answer fetch would pick a rung from density alone and be
superseded by that answer. `coverage(level)`, `holds(level, from, to)` and `publication` are
the per-frame reads, and `RatingChartSeries` caches one level's flattened draw list against
`coverageVersion`.

## Resolution and drawing

Resolution is chosen from visible span and plot width with four-times-wide up/down
hysteresis (`RatingChartLevel`), clamped to 0..54 and the 8-tile ceiling, with
`publication.allTimeLevel` used only at all time. A finer rung waits until `holds` the whole
window; the coarse candles stay up meanwhile, and the plot never mixes rungs or synthesizes
intermediate OHLC.

Covered tiles carry flat from their exact `carryRating`/previous close (a covered
zero-candle tile is a loaded-empty interval), `before_history`/`after_history`/
`unavailable` tiles draw nothing flat, and every candle is drawn final: the newest bucket in
a response was published when the server answered, and the chart is a snapshot of one reading
rather than a live feed with a mutable tail. A candle is centred on its bucket's middle, pulled
inside the publication's own `[first event, last event + 1]` bounds. The all-time window is
half-open `[first event, last event + 1)`, including the newest event's millisecond. A window
edge can cut through a bucket rather than landing on one, so the newest bucket
runs past the plot and its plain middle can fall outside it; the newest candle then vanishes for
exactly the rungs where it does, leaving the flat carry reading as "nothing since". Pulling the
mark to the bound it crosses (the last event, or the first at the all-time start) is stable
because it depends on the data, not on the window: a cut candle pans with the plot instead of
sticking to an edge. The mark is not re-centred on the part of the bucket before the last event —
a bucket's events are not spread over that interval, so a lone event late in a wide bucket would
draw its candle up to half a bucket away from it.

Candles are capped-width marks, not bucket-wide bodies. Flat connectors attach to their body
edges; painting and hit-testing use the same screen endpoints, while popup intervals retain
the actual bucket-boundary times. Adjacent buckets still get connecting ink, but their empty
carry interval does not produce a carry popup. Each covered tile is projected in full before
clipping, retaining offscreen neighbours as connector anchors; tile boundaries join at their
time-projected x, and non-covered tiles are never bridged.

`ChartYAxis` eases the vertical scale; rating
labels come from the stable target bounds while geometry keeps full precision. The scale is
view-local to one surface: the overlay is shared, but a new component clears the axis, so a fresh
open snaps to its first window instead of easing from the scale the previous visit left.
`ChartTimeAxis` owns the horizontal ticks — cadence from the span, text from the calendar: at a
sub-day step the first visible tick and each new UTC day print `MM-DD`, the rest print `HH:mm`
(`HH:mm:ss` below a minute), and a day step or coarser keeps the date in the fine label. Labels are
numeric UTC; an edge label is clamped into the plot rather than dropped.

Responses replace whole tile entries: an older epoch is dropped wholesale, an older
`(epoch, published_sequence)` never overwrites a newer mutable candle, and the all-time
collection is replaced as one when its rung coarsens.

## The popup: a candle or a flat carry

One selection's exact numbers, on demand. The foreground is one line: a candle's rating at the
bucket's start and end, with the signed change in a chip tinted green or red (the signed-delta
roles the score table already uses), or a carry's held value — both in the accent the rest of the
app gives a rating. The context follows underneath in the small, muted faces: the local date, with
its weekday whenever the line names one date; the time range, or the date range for a bucket of a
day or more; and the bucket's high and low — each of the two extremes only when it is not already
one of the two ends, since an extreme that repeats an end says nothing the ends did not.

A flat carry — the line the chart holds between two candles, or from a tile edge to the first or
last candle — gets the rating it holds as its headline and the period the line covers as the quiet
line under it. The period always carries times: a stretch inside one local date names that date (and
its weekday) plus the two clock times; one that crosses a date names both full timestamps, with no
weekday, because a range's single weekday would speak for only one end. A span shorter than a minute
keeps its seconds (and shorter than a second, its milliseconds), so a short range never reads with
both ends the same. The span is the line's own bucket-edge span (the end of one
bucket to the start of the next), not the instant the rating really changed: the wire carries no
event instant inside a bucket. Adjacent carries with the same rating are merged across a tile seam
so the popup names the whole stretch; a non-covered tile breaks the line and the span with it.

A pointer platform names the flat carry under the cursor first and otherwise the candle under it; a
touch platform names what the player tapped, and a tap on empty space — or a window that moved —
clears it. The carry is taken first because its ink is a hairline: a candle's reach is half a bucket
wide and its band includes the carry's rating, so otherwise the neighbouring candle would steal the
line's ends. The popup is painted by the overlay over the plot and changes no geometry.

The weekday names are the shared, abbreviated `date.weekday.*` keys, in ISO order (Monday first),
not a `java.time` formatter — TeaVM does not carry one. They ship English-only for now and are
listed in `assets/i18n/untranslated-keys.txt` until the locales translate them; adding a
translation is a property-file edit, never a Kotlin one.

**It hangs beside the pointer, and the selection is highlighted.** The popup is a floating box, so
it belongs next to the thing that asked for it: it sits to the right of the point that named the
selection — the cursor on a desktop, the tap on a phone — centred on it, flipping to the left when
the right does not fit, and clamped inside the plot. Beside, never over: the selection stands at
the anchor's x, and the chart paints that one candle brighter over a halo of its own range — or the
carry brighter and thicker — so the numbers and the ink they are about are visibly one thing. The
highlight and the popup are one selection: the chart view paints the selection the popup names, and
it is the only highlight there is.

**Two clocks, one question each.** The axis is UTC, because that is the grid the server published
and the buckets are cut on. The popup speaks the player's own clock, because "when did I play" is a
question about their day: it gives their date and their time of day, and says nothing else about
the offset it read. A device that cannot report an offset reads UTC — the axis's own clock.

The offset comes from `platform/PlatformTimeZone`, which the launchers install beside the zone
id they already report to the server: `ZoneId.systemDefault()` on Desktop and Android,
`Intl.DateTimeFormat` on the web. The offset is asked **per instant**, because a zone's rules
(deciding daylight saving) decide it; the popup never converts a zone's standard offset. A
selection reads its whole span at the offset of its start, so a stretch across a daylight-saving
change does not name two clocks at once.

The hit test and the paint share one candle area (`ratingChartArea`), so the candle a pointer
names is the candle the paint put there. It is the paint's answer in both axes: the pointer must
be over that candle's own high-to-low range, because the popup names one candle and hangs beside
the pointer, so a pointer far above or below the ink — or a tap on empty plot — must not summon
it. A candle too short to hit keeps a minimum band, so a flat one stays reachable. The horizontal
reach is the pointer's: a mouse picks the candle it is over (half a bucket), a finger picks the
nearest candle within a finger's width. A flat carry is picked from the carries the paint recorded
at the eased axis that drew them, within a finger's vertical band; horizontally its own span
decides, so a carry running off screen still names its whole period.

## Responses, phases, and failures

Each dispatched request carries a deadline; a request that outlives it is retired and
cancelled, because the web backend's `WebNet` never applies `HttpRequest.timeOut` and would
otherwise hold its slot forever.

The component paints one honest phase: loading, `empty` (a complete history with no event),
`empty_view` (a held window with no candle), `unavailable` (a held window of only non-covered
tiles), `not_ready` (a backfill still being prepared), `pending` (publication lag — never
empty), error (Retry drives the client's `retry` only while no request is open) or ready. The
phase chooses the copy, not the plot: E3 paints its chart whenever the window holds a covered
tile to map — candles, or the flat carry of a covered interval with no event in it — and reports
whether it did, so a covered gap (`empty_view`) keeps the grid, both axes and the flat carry
with its line centred over them, while `empty`/`not_ready`/`pending`/`unavailable` answer for
the whole window and paint their line alone.

An opening `loading` is silent for its first 250 ms — the surface and plot plate are already up — and
then, if the wait is real, paints a faint chart skeleton with a small caption in the time gutter
instead of a centered sentence. A fast all-time answer therefore goes straight to the chart; the
grace wake is the only extra frame a slow one asks for.

A server whose history endpoints are disabled — zj-server's `ZJ_RATING_HISTORY_ENABLED` is off
by default, so the routes are not mounted at all — answers the router's plain `404`; the client
classifies every non-200 into a typed `RatingHistoryFailure` (`404` → `NotFound`,
`5xx`/`429`/`503` → retryable `Server`, an over-deadline request → `Timeout`) and the overlay
paints its error-with-Retry state exactly as it does for any other outage. There is no
client-side flag to keep in step with the server: enabling it later recovers on a Retry, with no
app update.

## Chrome and lifecycle

The chart is a **full-screen surface**, not a floating card: its plate fills the window edge
to edge and the route is presented `Opaque`, so the surface it was opened from (the hub, the
leaderboard, or the root menu) is not modelled or painted beneath it (and the version stamp
steps aside). A chart is read across time, so the scarce resource is pixels; every piece of
chrome is arranged around the plot instead of the plot being fitted into a card. The plate bleeds under the display cutout while
the chrome lays out inside `SafeArea` with one design pad, so a camera hole or a rounded corner
never covers a control, the name or a tick label. The title band carries
`publication.display_name` when it names one, the zoom / All Time controls beside it when the
band has room, and the close control; the footer band exists only while there is a failure to
name — it carries that line and, when retryable, Retry — so a healthy surface hands everything
below the title to the plot. There is no outside to press, so the close control and system
back are the exits. The surface shows no publication timestamp or lag.

Continuous frames are requested only while a gesture/coast or the Y ease moves; with a request
open and the picture settled, the component schedules **one** wake at the deadline
(`Frame.wakeAfter` → `PresenceScheduler.scheduleAfter`), so the retirement still fires on a page
that is not painting. Nothing refreshes the chart on a settlement or a timer: the reading is a
snapshot taken when the surface opens (`ratingHistoryEnter` → `RatingHistoryClient.open`), and
reopening it takes another. The *reading* is new, but the *view* is remembered for the same
target: the route keeps its window and the rung it was drawn at past the entry, so closing the
surface and reopening the same player's chart — or relaunching with it — lands on the same range
and the same density, clamped to that history. The rung has to be remembered rather than
re-derived: the ladder keeps a rung across a wide hysteresis band, so a fresh density pick on
the same window can be one rung finer and split candles the player was just looking at. A
different target is a new surface and opens on all time: a window names absolute instants, so
replaying it onto another player's candles would be a positioning that reading never had. A
settled rated game updates the account's Elo, not the open chart.
