# pp-coach-tracker

**A phone camera that measures the pitch in meters.** Record a match with one phone on a tripod, mark the pitch once, and get every player's position in meters, tracked by **SAM 3.1**, even on amateur pitches with faded lines and poor light.

Built for the **Meta Global AI Developer Hackathon** (October 26 – November 4, 2026). The core is SAM 3.1 tracking plus metric calibration.

## How it differs from a common sports analyzer

1. **Meters, not pixels, from one phone.** The coach taps a few points or paints a few lines of the pitch on one frame. The worker solves the camera (homography) and follows it frame by frame as the phone pans, tilts or zooms (or keeps it fixed when the coach says the camera stays still), so every player's feet land in meters on the pitch. Common analyzers give pixel heatmaps, or need fixed installations, several cameras or GPS vests.
2. **Works on real, bad pitches.** When the lines are faded or the light is poor, the coach paints over a reference photo or a video frame: "this is a line". Each stroke goes to SAM 3.1 as a point-prompted object that it follows through the video, next to a separate `white line` concept request; the masks confirm or correct the camera.
3. **Nothing to train, nothing to label.** SAM 3.1 finds each team with a short noun-phrase prompt from the jersey color (`player in red jersey`) and tracks it through the video. A new league, new kits or a new pitch need no dataset and no model.
4. **It measures; the coach decides.** Muse Spark turns the measurements (possession, meters per minute, sprints, team width and length) into short findings linked to the exact moment of the video. It never sees player names.
5. **It says where it did not measure.** Frames without the pitch, camera cuts and stretches without calibration are flagged and left out. There is no local fallback that invents data: if the Meta API fails, the analysis fails with a clear message.
6. **Privacy by design.** Video-use consent before the first upload, withdrawable in the app, and no names sent to Meta.

## How it works

```
Android app ──chunks──▶ API (PHP + MySQL) ◀──jobs── Worker (Python) ──▶ Meta Model API
 record, 10 fps copy,     resumable upload,          SAM 3.1 tracking,       SAM 3.1
 pitch marks, consent     queue, results              pitch in meters,        Muse Spark
                                                      metrics, findings
```

- **App** ([`android_native/`](android_native/README.md), Kotlin + Jetpack Compose): records or imports a match, prepares a light analysis copy on the phone (10 fps, up to 720p, 1- or 5-minute chunks with overlap, plus a per-frame pitch mask), uploads it in resumable 8 MB parts, lets the coach mark the pitch and shows the analysis.
- **API** ([`api/`](api/README.md)): one HTTP contract with two implementations that pass the same tests: PHP 8.3 with no dependencies for production, and Node for development.
- **Worker** ([`worker/`](worker/README.md), Python): sends each chunk to SAM 3.1 (one noun-phrase concept per request: each team and the ball), joins the tracks across chunks, solves the pitch in meters from the coach's marks, computes the metrics and asks Muse Spark for findings with a strict JSON schema.

The architecture, with diagrams, is in [docs/arquitectura/](docs/arquitectura/README.md) (in Spanish).

## Status (October 30, 2026)

| | |
|---|---|
| App, API (Node and PHP) and worker | Built, with automated tests passing (app 62, Node 23, PHP 26, worker 92) |
| SAM 3.1 and Muse Spark | **Verified with the real Meta Model API** on October 30: two 15-minute clips end to end, masks decoded with Meta's `meta-sam-parser`. Measured cost: about US$0.36 per minute of video (3 concepts at 10 fps) |
| Results | [docs/results/](docs/results/README.md) (in Spanish): SAM 3.1 against a local detector and tracker used only during development (never shipped). SAM keeps one track per player and sees the ball in 85 % of frames, against 17 % |
| Wide and 4K video | SAM resizes every frame to 1024 px on its long side, so players in a panorama vanish. The worker now splits wide video (a panorama, or a long side over 2048 px) into overlapping tiles of up to 1024 px over the pitch and joins the tracks again: 0 → 19 players per frame on a 3840×800 panorama, at one SAM request per tile. The app still sends at most 1080p, which is not split |
| Still or moving camera | The coach ticks "the camera stays still the whole video" (tripod or panorama) and the worker uses one homography for the whole video; otherwise it follows the camera frame by frame |
| Saved pitches | A pitch is saved once with its length and width in meters and reused on every visit |
| Lines with SAM (`PP_PITCH_SAM`) | Off: the `white line` concept and its masks work with the real API; whether SAM follows a painted stroke from a photo into the video is still unmeasured |
| Production API at https://ppcoachtracker.pepemxl.com | Deployed on October 30 and updated the same day (`/health` reports the database) |
| Production worker | Running on the development host (Docker in WSL) since October 30 |
| Run viewer (local only) | Shows a run over its video, and compares two runs of the same video side by side, overlaid or only where they differ |
| Per-player statistics | The squad (numbers, positions, an alias that never leaves the phone) and each player's 17 stats per match: the coach's values win over the analysis, totals, per 90 minutes and pass accuracy. A report by squad number can be imported, and the coach links each analysis track to a player so their minutes come from the video. A **Stats** tab sorts the squad by any stat over the chosen matches (all, the last 3/5/10, a range of dates or picked ones), each tagged player shows distance, meters per minute and top speed from the video, and each match has its starting lineup on a pitch (9 formations of 11 and 4 of 7) |
| Video retention | `api/php/bin/retention.php` deletes a clip's video 24 h after its analysis and after 30 days at most, keeping the analysis |

## Running it locally

From `api/`: `npm run db:up`, `npm run php:up && npm run php:migrate`, `npm run worker:up` and `npm run viewer:up`. Tests: `npm test`, `npm run test:php`, `npm run worker:test`, and `gradlew :app:testDebugUnitTest` in `android_native/`. Secrets go only in a `.env` at the root that is never committed; [`.env.example`](.env.example) lists the names. Details in [docs/arquitectura/05-despliegue.md](docs/arquitectura/05-despliegue.md).

## Prior work and what was built during the hackathon

**Before the hackathon (Oct 20–25, 2026):** we designed pp-coach-tracker and built a first prototype, which never ran SAM 3.1. From that work we kept only documentation: the design docs and lessons learned in `docs/`, the legal texts in `docs/legal/`, and the step-by-step build prompts in `docs/prompts/`. Commit `2005a96` is the starting point: docs only, no application code.

**During the hackathon (Oct 26 – Nov 4, 2026):** all the code in this repository was written from scratch with an AI coding agent driven by those prompts: the Android app, the API, the analysis worker with SAM 3.1 and Muse Spark and no local model fallback, the pitch calibration and the metrics in meters. The git history shows every step.

**Third-party components:** permissive licenses only (MIT, BSD, Apache-2.0), plus Meta's own `meta-sam-parser` (SAM License) to decode SAM 3.1 masks; see [THIRD_PARTY.md](THIRD_PARTY.md). The vision and language models are Meta's, used through the Meta Model API.

---

**En español:** pp-coach-tracker convierte el video de un celular en posiciones en metros. SAM 3.1 sigue a los jugadores y la calibración que marca el entrenador (puntos o líneas pintadas, también en canchas con líneas borradas o poca luz) los pone sobre la cancha. Muse Spark explica lo medido. La documentación del proyecto está en [docs/](docs/README.md).
