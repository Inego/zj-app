# Sound effects

The 22 WAV files in this directory are the game's table cues. They were
generated procedurally for this project — filtered noise and damped resonances
for tile and claim sounds, simple synthesized tones for win, draw, and timer
stings — and contain no third-party samples.

They are original work covered by the project's CC BY-NC-SA 3.0 HK licence; see
`NOTICE`. The generator is not part of this repository; its provenance is kept
with the private zj-server work.

Format: mono, 44.1 kHz, 16-bit PCM WAV. Replacing a cue is a data edit: keep the
file name and format, and `SoundCatalog` needs no change.
