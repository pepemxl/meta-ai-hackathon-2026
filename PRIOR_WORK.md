# Prior work and what was built during the hackathon

The Meta Global AI Developer Hackathon ran from the kickoff on **October 26, 2026** to the submission deadline on **November 4, 2026**. The organizers allowed code written before the kickoff, as long as the submission says what is prior and what was built during the event. This page does that. The git history is the evidence: small commits with their real dates, never rewritten.

## In short

| | Before the kickoff (Oct 20–25) | During the hackathon (Oct 26 – Nov 4) |
|---|---|---|
| Idea and design | The idea (soccer analysis with SAM 3.1 and Muse Spark), the product design, the visual style, the chunk format, the privacy approach and the legal texts | Refined as the build went: the pitch in meters from the coach's marks, painted lines, play area, surface sample, rotation |
| Code | A first prototype, **outside this repository**. It never ran SAM 3.1: it had a local object detector, its own tracker and rule-based insights | **All the code in this repository**, written from scratch: Android app, API (Node and PHP), analysis worker, run viewer. Nothing from the prototype was copied |
| Documentation | The design docs, lessons learned, legal texts and build prompts, as the starting point (see below) | Architecture updated to what exists, measured results, a new lesson on wide video, release notes for 7 versions, the demo mode |
| Deployment | — | Production API at https://ppcoachtracker.pepemxl.com (Oct 30), production worker, daily video-retention job |
| AI models | — | SAM 3.1 and Muse Spark through the Meta Model API, verified with the real API on Oct 30 |

## Before the kickoff

- **Design and planning (from Oct 20):** we chose the project, wrote the product design, the visual style of the app, the chunk format for long videos, the privacy approach and the legal texts (video-use consent, privacy notice, players' consent form, in Spanish and English).
- **A first prototype (not in this repository):** an Android app and a worker with a local object detector (an AGPL-3.0 model, described in `docs/results/01`), an IoU tracker and rule-based insights. It never called SAM 3.1 or Muse Spark. Its lessons (thresholds, contracts, pitfalls) were written down in `docs/learnings/` and are marked **Antecedente** (background) there.

## The starting point: commit `2005a96` (Oct 28)

The repository starts with a README (`00bd029`, Oct 27), a `.gitignore` (`01c6c4f`, Oct 27) and **commit `2005a96` (Oct 28): documentation only, no application code** (43 files under `docs/` plus `.env.example`):

- `docs/arquitectura/`: the planned architecture (it has been rewritten since to describe what exists);
- `docs/diseno.md`: the visual style;
- `docs/learnings/01–03`: the prototype's lessons (background);
- `docs/legal/`: the legal texts;
- `docs/prompts/00–13`: the step-by-step build prompts for an AI coding agent, with acceptance criteria;
- `docs/08`, `10`, `11`, `12`, `13`: video use, player privacy, per-player stats, the chunk format and the development environment.

That documentation comes from the pre-hackathon design and prototype, and **part of it was finished after the kickoff and before the first line of code** (for example, the stock-video license reviews in `docs/08-uso-de-video.md` are dated Oct 27). On Oct 28, right after it, the planning notes, the prototype's screenshots and its improvement notes were removed from the repository; they remain in the history.

## During the hackathon

All of this was written from Oct 28 to Nov 3 in 109 commits after `2005a96`, each one co-authored with an AI coding agent (Claude Code, with Claude Opus 5.5), following the prompts in `docs/prompts/`:

| Component | What it does | Size |
|---|---|---|
| Android app (`android_native/`, Kotlin + Jetpack Compose) | Matches, record or import, a 10 fps analysis copy in chunks with a per-frame pitch mask, resumable upload, consent, the pitch marks (points, painted lines, reference pictures, play area, surface sample), the analysis, squad and per-player stats, demo accounts, rotating a video | ~11,600 lines, 86 unit tests |
| API (`api/`): PHP 8.3 for production, Node for development | Login, resumable 8 MB part uploads, job queue, consent history, results, video retention; one contract, both pass the same tests | ~5,500 lines, 27 PHP and 24 Node tests |
| Analysis worker (`worker/`, Python) | SAM 3.1 by concept (each team, the ball), wide video split into tiles, tracks joined across chunks, the pitch mask, the pitch in meters from the coach's marks, metrics, Muse Spark findings with a strict JSON schema, cost tracking. No local model fallback | ~8,400 lines, 117 tests |
| Run viewer (`viewer/`, React, local only) | Shows a run over its video and compares two runs | ~1,800 lines |

- **App versions:** 0.1.0 (Oct 28) to 0.4.3 (Nov 3), each with its release notes in [docs/versions/](docs/versions/README.md).
- **Measured results:** [docs/results/](docs/results/README.md), SAM 3.1 on real video: players, ball, possession, cost (about US$0.36 per minute of video), wide video and the pitch in meters.
- **Our own test footage:** two real pitches (artificial turf and dirt) recorded on Nov 3 with the app and with the phone's camera; small frames of them are test fixtures in `worker/tests/fixtures/real/`.
- **AI-generated images and videos** for screenshots, made on Nov 2 with Meta muse-image-1.0 and Meta AI (Vibes), labeled as such ([docs/08-uso-de-video.md](docs/08-uso-de-video.md)).

## Prior code used during the hackathon, and what was not built

- **The prototype's local detector was run once, outside this repository,** on Oct 30, to compare it with SAM 3.1 on the same clips ([docs/results/01](docs/results/01-cv-local-vs-sam31.md), where it appears as `local_cv`). It is not part of the submission, ships nowhere and no analysis in the app comes from it.
- **The local SAM simulator** planned in `docs/13-entorno-desarrollo.md` and `docs/prompts/08a-simulador-sam.md` (a stand-in for the SAM API on top of the prototype's detector, to save credits) **was never built.** The worker was developed against synthetic SAM streams in its tests and the real Meta Model API. The app and the viewer still recognize a `sam_simulator` label, which no run has used.

## Third-party components

Permissive licenses only (MIT, BSD, Apache-2.0, ISC), plus Meta's own `meta-sam-parser` (SAM License) to decode SAM 3.1 masks. Every dependency is listed in [THIRD_PARTY.md](THIRD_PARTY.md). The vision and language models are Meta's, used through the Meta Model API.

---

**En español:** antes del kickoff (20–25 de octubre) se diseñó el proyecto y se hizo un prototipo fuera de este repo, que nunca usó SAM 3.1. De ese trabajo solo se conserva la documentación de `docs/`, que entró en el commit `2005a96` (28 de octubre, sin código) y se terminó en parte después del kickoff. Todo el código del repo (app, API, worker y visor) se escribió durante el hackathon, del 28 de octubre al 3 de noviembre, en 109 commits hechos con un agente de programación. El detector local del prototipo solo se corrió una vez, fuera del repo, para comparar con SAM 3.1; el simulador local de SAM no se construyó.
