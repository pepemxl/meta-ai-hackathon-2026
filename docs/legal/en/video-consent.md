# Consent to use video for the analysis

> Version `2026-10-27`. Draft, **not legal advice**. English translation of the [Spanish original](../consentimiento-uso-de-video.md); if they differ, the Spanish version prevails.

This is the text the coach accepts before sending video to PP-Coach-Tracker. It comes in two forms:

- **Short text in the app:** the dialog with checkboxes.
- **Full terms:** what the short text sums up. The dialog links to them ("Full terms").

---

## Short text in the app

**Before you send your first video**

PP-Coach-Tracker uses your videos only to generate the analysis of your matches. To send it, please confirm:

- [ ] **I have the right to use this video.** I or my club recorded it, or I have permission from whoever did.
- [ ] **I have the consent of the people who appear in it**, and of their parents or guardians if they are minors.
- [ ] **I authorize PP-Coach-Tracker to process the video to generate analytics**: detecting and tracking players and the ball with artificial intelligence (including Meta models) and computing tactical statistics and insights.
- [ ] **I have read the [privacy notice](privacy-notice.md)** and the [full terms](#full-terms).

We don't use your video to train models, publish it or share it with other users. We delete the video from the server after the analysis, and you can withdraw your consent at any time from Settings.

[ Cancel ]   [ Accept and send ]

**Reminder on later uploads** (one line above the "Send" button):

> By sending you confirm you have the consent of the people who appear in the video.

---

## Full terms

### 1. What you accept

By checking the boxes and tapping "Accept and send", you (the **User**, usually the coach or the club) accept these terms for every video you send from your account while this version is in force. If they change, the app will ask you to accept them again.

### 2. Your declarations

You declare, under your responsibility, that:

1. **Rights to the video.** You recorded the video, your club recorded it, or you have permission from whoever did. It is not a TV broadcast or third-party material used without permission.
2. **Consent of the people.** Before recording, you informed the players, referees and other identifiable people who appear, and obtained their consent to record them and to have the video analyzed with artificial intelligence tools. You can use the [consent form](players-consent-form.md) for this.
3. **Minors.** If minors appear, the consent was given by their father, mother or guardian, and you keep it.
4. **Evidence.** You keep those consents while the video or its results are in PP-Coach-Tracker, and you will show them to us if a person or the authorities ask.
5. **Withdrawal.** If someone withdraws their consent, you will delete the videos and results where they appear, or ask us to do it.

If any declaration turns out to be false, you are responsible to the people who appear and to PP-Coach-Tracker.

### 3. What we use the video for (purpose)

We use the video **only to generate the analytics of your account**:

1. Receiving it and storing it temporarily on our server.
2. Detecting and tracking the players and the ball with **SAM 3.1** (Meta Model API) or, if it is not available, with a local tracker.
3. Projecting the positions onto the pitch and computing metrics: heat maps, team shape, distances, pressing and events.
4. Generating tactical insights in text with **Muse Spark** (Meta Model API). Muse Spark receives metrics and tags such as "J3 (#7)", **never names**. It may also receive frames or video fragments for qualitative comments.
5. Showing you the results in the app and letting you tag the players.
6. Diagnosing failures in the analysis of your own video (for example, if a chunk arrived damaged).

We do nothing with the video outside this list. In particular:

- We **do not** use it to train or improve AI models, ours or third parties'.
- We **do not** do facial recognition or identify people by their physical features; players are identified by jersey number and by the tag you give them.
- We **do not** publish, sell or share it with other users or for advertising.
- We **do not** use it in demos, PP-Coach-Tracker materials or social media without an additional, written permission.

### 4. The license you give us

You give us a **non-exclusive, free, revocable license limited** to the purpose in section 3, which includes copying the video to our servers and to our providers' servers, converting its format and processing it. The license ends when you delete the video or your account, except for backup copies, which are removed in their normal cycle (section 6).

The rights to the video remain yours or of whoever recorded it. The analytics generated (metrics, tracks and insights) are yours to use with your team.

### 5. Providers that process the video

| Provider | What it receives | What for |
|---|---|---|
| Cloud Provider | Video chunks and their metadata | API server and storage |
| `[Modal / VPS]` | Video chunks, during the analysis | Running the pipeline |
| Meta Platforms (Meta Model API) | Video or frames (SAM 3.1); metrics and tags without names (Muse Spark) | Segmenting and tracking players; writing insights |

Some providers are outside Mexico (for example, in the United States). We require them to process the video only to provide us the service. With Meta we use an API mode in which **the data may not be used to train their models** `[confirm in the current Meta Model API terms]`.

### 6. How long we keep it

| Data | Period |
|---|---|
| Video (`.mp4` chunks) | Until the analysis finishes and at most `[30]` days after it was uploaded. If the analysis fails, `[30]` days to retry |
| Chunk metadata (`.yaml`: times and frames, no image) | As long as the clip exists |
| Tracks, metrics and insights | As long as the clip or your account exists |
| Copy at the AI providers | For as long as the processing lasts, according to their terms `[confirm Meta's period]` |
| History of this consent (each acceptance and withdrawal) | As long as your account exists and `[5]` years after, as evidence |

### 7. Deletion and withdrawal

- You can delete a clip, a match or your whole account from the app, or write to `[PRIVACY EMAIL]`.
- Deleting a clip removes its video, tracks, metrics and insights from the server within `[7]` days.
- You can withdraw this consent from Settings. It stops the analysis of new videos, and uploads still waiting are cancelled. What was already processed is deleted if you ask.

### 8. Quality of the results

The analytics are generated automatically and may contain errors (for example, mixing up two players or losing the ball). They are an aid for the coach, not an official assessment of anyone's performance, and must not be used for medical, hiring or disciplinary decisions without human review.

### 9. Prohibited content

Do not send videos that are not of soccer practices or matches, or videos with nudity, violence, locker rooms or bathrooms, or recorded without people's knowledge. We may delete that content and suspend the account.

### 10. Changes and contact

If we change these terms we publish a new version and the app will ask you to accept it before the next upload. Controller: `[CONTROLLER]`, `[ADDRESS]`, `[PRIVACY EMAIL]`.
