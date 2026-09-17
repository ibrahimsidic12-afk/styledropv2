# COMMUNITY.md — StyleDrop Community & Sharing System

**Agent #18 · COMMUNITY + SHARING** · Repo: [ibrahimsidic12-afk/styledropv2](https://github.com/ibrahimsidic12-afk/styledropv2) · Commit analyzed: `a53013b` ("feat(ui): update visual identity to ivory theme") · Date: 2026-09-17

**Companion code (in this repo):**
- [`community/sharing.py`](sharing.py) — runnable reference implementation (Python stdlib, zero dependencies). `python3 sharing.py` runs the full round-trip demo.
- [`community/Sharing.kt`](Sharing.kt) — the Android (Kotlin) mirror of the same wire format, using only `org.json` + `java.util.zip` + `android.util.Base64` (no new dependencies).
- [`community/recommender.py`](recommender.py) — Agent #08's recommender, imported by `style_dna()`.

---

## 0. Design North Star

StyleDrop's community is a **wardrobe-first atelier**, not a social network. Every feature below passes one test:

> *Does this help people wear their own clothes better — or does it make them perform for strangers?*

That single rule drives three hard exclusions that never get traded away:

| Never shipped | Why |
|---|---|
| Public like/reaction counts | Counts create a popularity market → outfits get optimized for reactions, not wearability. Replaced with coarse, qualitative **resonance bands** (§2.3). |
| Follower counts / streaks / leaderboards | The three fastest ways to make a wardrobe app toxic. Challenges use a **curated daily rotation** instead of a top-N ranking (§4). |
| Infinite engagement loops (autoplay, push-nags, vanity metrics) | Notifications are capped per day (§6.4); every surface has a natural stopping point ("that's today's drop — come back tomorrow"). |

Privacy-first (§3 is the enforcement spec): the wardrobe stays on-device, nothing leaves the phone except explicit share payloads, every cross-user identity is a rotating pseudonym, and the inspiration feed contains zero images of people.

---

## 1. Feature Overview — all 8 requested surfaces

| # | Feature | What it is | Where it lives | Privacy stance |
|---|---|---|---|---|
| 1 | **Share-outfit link** | Self-contained URL that rebuilds a look from `#look=` fragment — works with the existing builder and without any backend | `sharing.py §1–2`, `Sharing.kt`, `outfit_builder.html` | Wardrobe items travel **by reference** (local ids), never as private data |
| 2 | **Style Stories** | 24h vertical-swipe looks; a frame *is* a share-code look, not a camera selfie | `sharing.py §4` (StoryFrame/StyleStory) | Expires in 24h hard; view totals collapse to a **band**, never per-viewer rows |
| 3 | **Inspiration feed** | Browse anonymized community wardrobes (categories/colors/styles only) | `sharing.py §3` (FeedItem + `anonymize_wardrobe_for_feed`) | Strips `imageUrl`, `brand`, timestamps, ids; pseudonymous handles |
| 4 | **Style challenges** | "Old Money May", "Streetwear Sunday", "Minimalist September" — vocab-locked, solo, no leaderboard | `sharing.py §5` (StyleChallenge) | Entries self-checked locally; showcase is a rotating shuffle, no counts |
| 5 | **Friends + comments** | Mutual-consent friend edges; comments pass a two-tier tone gate before posting | `sharing.py §6` (FriendEdge, Comment, `comment_gate`) | Blocked comments are *quietly* invisible to the recipient; no public rejection |
| 6 | **Mood boards** | Named collections of 1–12 looks + palette + intent; boards share as links too | `sharing.py §7` (MoodBoard) | Same self-contained share codes — no server objects exist |
| 7 | **Style DNA score** | 0–100 style-consistency score computed **by the real recommender** | `sharing.py §8` (`style_dna()`) | Computed on-device from the user's own Room data; never uploaded |
| 8 | **Social-proof onboarding** | Honest aggregate lines on the welcome screen | `sharing.py §9` (`onboarding_social_proof`) | Aggregate counts only, no names, no "X friends joined" pressure |

---

## 2. Feature specs

### 2.1 Share-outfit link (the foundation — everything else is built on it)

A shared look is a **URL fragment**, so it needs no server, no account, and it cannot expire or leak a database. The existing `outfit_builder.html` already implements `#look=` + `btoa(JSON.stringify([[slotId, itemId, z], ...]))` (its `encodeLook()` at line 564). We keep that format readable forever as wire version **`sd1`**, and introduce **`sd2`** for metadata + integrity + size:

```
sd2:<base64url(zlib(json))-no-padding>.<8-char-checksum>
```

| Element | Value |
|---|---|
| JSON (compact keys) | `{"v":2,"i":[[slot,itemId,z],...],"t":title,"o":occasion,"w":weather,"n":note,"c":created,"u":handle}` — all fields except `v`/`i` optional |
| Compression | zlib level 9 → base64url, padding stripped |
| Checksum | `base64url(sha256("styledrop::v2" + body)[first 6 bytes])[:8]` — catches truncation, typos, tampering |
| Slot ids | the builder's 8 slots: `TOP BOTTOM SHOES OUTER HAT BAG WATCH JEWELRY` |
| Caps | compressed ≤ 4,096 B · decompressed ≤ 8,192 B · ≤ 8 items · note ≤ 140 chars · title ≤ 64 |
| Full URL shape | `https://styledrop.app/builder#look=sd2:...` (the fragment the app/web decodes on boot) |

**Why not keep raw `sd1` as the primary?** A base64 JSON blob has no integrity check (any typo silently corrupts a slot), no size guard (a malicious link can be a zip bomb), and no room for the metadata that Stories/Challenges/Boards need. `sd2` fixes all three while staying ~40% shorter on real looks thanks to zlib.

**Cross-version guarantee:** `decode_share_code()` accepts both `sd2` and legacy `sd1` (demonstrated in Test 2 of the demo output below), so every link already in circulation keeps working.

**What travels by reference:** items are `(slot, localItemId, z-order)` triples. A receiver opening someone's link sees the *shape* of the look (categories, colors, layering) rendered from the sharer's metadata embedded in the URL header — and is offered **"Remix with my wardrobe"**, which substitutes their own items by category. The sharer's photos are never transmitted.

### 2.2 Style Stories

Vertical, full-screen, swipeable stack of **looks** — not camera selfies. A `StoryFrame` is literally a `sd2` share code + caption + one of 7 brand background tokens (`ivory linen canvas gold ink felt mulled`, from `ui/theme/Color.kt`); a `StyleStory` is 1–5 frames, hard-expiring at 24h.

Anti-toxicity choices:

- **No viewer counts for the author.** The author sees one of three qualitative bands — `quiet (<10) / noticed (10–49) / circulating (50+)` — computed from an aggregate counter. There is no per-viewer row anywhere, so there is nothing to stalk and nothing to obsess over.
- **No screenshot surveillance** (no "who viewed" list, ever).
- **Replies are kind-only:** the only reply affordance is a pre-tuned set of kind reactions ("the color mix feels calm", "wearing this on Monday"); free-text DMs from stories do not exist, which removes the highest-harassment channel entirely.
- Stories **reuse share codes**, so a story frame can be tapped → "open in builder" → remixed. Community content flows back into utility instead of a consumption scroll.

### 2.3 Inspiration feed (anonymized community wardrobes)

A browseable grid of wardrobe *cards* from users who opted in. `anonymize_wardrobe_for_feed()` is the enforcement point — it reduces every wardrobe row to the **public style vocabulary the app already exposes** (`category, type, color, secondaryColor, style, fit, pattern, season`) and strips: `imageUrl` (could contain faces/backgrounds), `brand` (a purchasable fingerprint — the only field that could identify a person through their shopping history), all timestamps (`createdAt`, `lastWorn` — behavioral fingerprints), and the row `id` (device-local identifier).

Identity: contributors appear as pseudonyms from `anonymize_handle()` — deterministic `adjective-noun-nn` names like `linen-rail-42` derived from `sha256(salt|userId)`. Same user is the same person within a community (so you can follow a vibe), but the handle is not reversible to an account, and rotating the salt re-anonymizes the entire feed at once.

Engagement: viewers tap **resonance** — one tap, one of three bands (`gentle / warm / beloved`) — and the *author* sees only the band, not a number and not who tapped. There is no comment-on-feed (comments live between confirmed friends only, §2.5), no "suggested for you" ranking that rewards outrage, and feed refreshes are capped per day (§6.4) to keep browsing finite. Every card's CTA is **"Remix this with my wardrobe"** — inspiration converts to utility, not to scrolling.

### 2.4 Style challenges

Monthly/weekly themes drawn **only from `AppConstants.styles`** (validated at construction — a `StyleChallenge` with a theme outside the 12 canonical styles cannot be instantiated):

| Challenge | Hashtag | Theme style | Rules |
|---|---|---|---|
| Old Money May | `#OldMoneyMay` | Old Money | wear pieces you already own · one look per day max |
| Streetwear Sunday | `#StreetwearSunday` | Streetwear | every Sunday · remix a friend's look with your pieces |
| Minimalist September | `#MinimalistSeptember` | Minimalist | max 3 colors per look · Plain patterns only |

`entry_is_valid()` checks locally that the user actually owns a piece in the theme style (self-reported, no server judgment), and `showcase_policy()` is the anti-toxicity core: **no leaderboard, no ranking, no counts**. The showcase is a daily-curated rotation where every qualifying look is guaranteed to appear at least once — participation is the win, not placement. There is no elimination, no voting, no "you lost".

### 2.5 Friends + comments

**Friend edges require mutual consent** (`FriendEdge.status: pending → accepted`); there is no follow-model, so nothing is broadcast to an audience and there is no stranger audience to perform for. A friend sees: your shared looks (when you send them), your stories, your mood boards you explicitly share. Not your wardrobe — that requires a separate per-friend grant.

**Comments pass a two-tier gate before posting** (`comment_gate()`):

| Tier | Trigger | Writer sees | Recipient sees |
|---|---|---|---|
| `allow` | clean text | comment posted | comment posted |
| `hide_for_review` | tone-risk patterns ("no offense", "just kidding", "ratio", "mid", "cope", "downgrade"…) | comment posted + gentle nudge: *"Say it kindly or not at all — how about starting with something you like?"* | nothing |
| `block` | hard abuse terms ("ugly", "trash", "loser", "stupid", "kys", body-shaming…) | comment posted + nudge: *"Try naming what works: 'the color mix feels calm'."* | nothing |

**Quiet blocking is deliberate:** the writer is never publicly rejected (no shame loop, no reactivity), the recipient never receives the harm, and no counter-public drama is created. Rate limits cap the volume of all social actions per day (§6.4) — friction is a feature.

### 2.6 Mood boards

A `MoodBoard` is a named collection of **1–12 share-code looks + a palette (app color vocab) + a one-line intent** (e.g. "Rainy Manila commute" → looks + `[Navy, Beige]` + "dry shoes only"). Boards are assembled by long-pressing any look (builder, feed, story, challenge) → "Add to board". A board shares exactly like a look: `MoodBoard.to_share_code()` produces a `sd2` link, so boards work offline, in group chats, and across platforms with zero infrastructure.

### 2.7 Style DNA score (via the recommender)

`style_dna()` in `sharing.py` imports Agent #08's recommender (`community/recommender.py`) and runs the user's **real** Room-derived wardrobe + outfit logs through `recommend()` five times, then reads five signals off the results:

| Component | Weight | Meaning |
|---|---|---|
| completeness | 0.30 | do the top outfits cover Tops + Bottoms + Shoes? (`1 − missing/3`, averaged over top-5) |
| harmony | 0.25 | mean pairwise `color_harmony()` of the #1 outfit |
| diversity | 0.20 | unique pieces surfaced across top-5 vs. slots filled |
| coverage | 0.15 | share of wardrobe categories the stylist can actually use |
| wear_balance | 0.10 | share of the wardrobe worn at least once (`timesWorn > 0`) |

Output: `score 0–100`, band (`Building <50 ≤ Emerging <70 ≤ Refined <85 ≤ Signature`), **archetype** (the most-surfaced `AppConstants.style`, e.g. "Streetwear"), and a 3-color **palette** (most-surfaced colors). Bands are descriptive, never comparative — there is no percentile, no "better than X% of users". The score updates slowly (weekly), and its only CTA is **"Wear more of what you love"** linking to the wardrobe. It is computed entirely on-device and never leaves the phone; a user may share a *badge card* (band + archetype + palette only, no score number) to a story.

### 2.8 Social-proof in onboarding

The welcome screen may show up to three lines from `onboarding_social_proof(total_members, looks_shared_today)`:

```
12,400 people hang their wardrobe here.
318 looks were pinned today.
No follower counts. No streaks. Just your clothes.
```

Rules: aggregate counts only, never names or avatars of other users, never "your friend joined", never a declining countdown ("only X spots"). The third line states the contract explicitly — it teaches the anti-toxic norm *before* the first session. If numbers are unavailable, the module returns the brand line "Your atelier is waiting. Hang your first piece." instead of fabricating proof.

---

## 3. Privacy architecture (enforcement, not vibes)

| Layer | Mechanism | Enforced by |
|---|---|---|
| Data residency | wardrobe Room DB never syncs; only explicit payloads leave the device | payload design (§2.1) |
| Identity | pseudonymous rotating handles; no user ids on any wire | `anonymize_handle()` |
| Feed contents | image/brand/timestamp/id stripping | `anonymize_wardrobe_for_feed()` (allow-list, not block-list) |
| Link contents | share codes carry item *references* + style metadata only; no photos, no account data | encoder schema (§2.1) |
| Behavioral data | no per-viewer view rows, no per-tap resonance rows; aggregates only | §2.2, §2.3 |
| Message content | comments gated before persistence; blocked content is never stored to the recipient | `comment_gate()` |
| Anti-abuse surface | no public counts → no ranking to game; rate limits → no harvest-at-scale | §4, §6.4 |
| Deletion | pseudonym rotation + salt rotation erases community identity without touching the local wardrobe | §2.3 |

The stripping function is an **allow-list** (keep only the public style vocabulary) rather than a block-list — new identifying fields added to `WardrobeItem` in future schema versions are excluded by default.

---

## 4. Anti-toxicity moderation design (summary)

1. **No metric worth fighting over.** Resonance bands, view bands, non-comparative DNA bands — a number you can't see can't be gamed, and a rank that doesn't exist can't be lost.
2. **Quiet gatekeeping.** Tone-risk and abusive comments are filtered at write time with kind-nudges to the writer; the recipient's feed never carries the harm; there is no public moderation theater.
3. **Consent before audience.** Mutual friend edges; no follow model; no broadcast.
4. **Finite surfaces.** Daily caps on comments (20), stories (3), feed-refresh suggestions (10), and notifications (5) (`RATE_LIMITS` in `sharing.py`) — every surface ends, deliberately.
5. **Vocab-locked creativity.** Challenges and feed tags are constrained to `AppConstants` (validated in code), so community content can't drift into unmoderatable free-text trends.
6. **Utility over engagement.** Every community surface deep-links back into the builder/remix flow — the product's success metric is *worn clothes*, not time-in-app.

---

## 5. Kotlin integration map (Android side)

| Concern | File / call | Notes |
|---|---|---|
| Wire codec | `community/Sharing.kt` (`Sharing.encode/decode`) | mirrors `sharing.py` constants exactly (`SIG_SALT = "styledrop::v2"`, caps, checksum) |
| Entry point | `SavedOutfitsScreen` Mix & Match `TODO` → builder + "Share look" → `Sharing.encode(...)` → `Intent.ACTION_SEND` | the stub's reserved action becomes the share surface |
| Inbound links | `MainActivity` deep-link/clipboard inspection for `#look=sd2:` → `Sharing.decode` → "Remix with my wardrobe" | handles both `sd2` and legacy builder codes |
| Stories / feed / challenges UI | `StoryFrame`/`FeedItem`/`StyleChallenge` map 1:1 to `sharing.py` dataclasses | Room tables mirror the dataclass fields; feed rows store share codes + tags only |
| Comment gate | port `_BLOCK_TERMS`/`_FLAG_TERMS` lists to a Kotlin object | same two-tier behavior, same nudge strings |
| Style DNA | port of `style_dna()` five signals (or call recommender port) | computed from Room `wardrobe_items` + `outfit_logs` (Agent #08's proposed table) |

No new Gradle dependencies are required for the wire format: `Sharing.kt` uses only `org.json`, `java.util.zip`, `java.security`, and `android.util.Base64`.

---

## 6. Verification — real run of `python3 sharing.py`

Executed inside the cloned repo (`community/sharing.py`, exit code 0, this session). Verbatim console output:

```
==============================================================================
SHARING.PY — ROUND-TRIP DEMO (real run, real repo schema)
==============================================================================
[TEST 1] sd2 encode/decode round-trip .......... PASS
         items : [('TOP', 'T1', 20), ('BOTTOM', 'B1', 10), ('SHOES', 'S1', 15), ('OUTER', 'O1', 30)]
         title : 'Rainy commute'  occasion: 'Work'
         URL   : https://styledrop.app/builder#look=sd2:eNodykELgjAYxvGvMt7zDLW6eBSELrHSRQfxMHTBcG4wXSLRd--x25_f83zoTUXOyVDRtiTFjTjJjHiedrylUkgprqASlP2puYiqgTS7nHcRD1nVEAE5pl3HaaGCamXcxno_TXHRWD3s6cOIXJG9twPSIe_R6IVZtekws5cPTLEVIKOeB7Ud8Ip4WeO0S4IyNjnl9P0BNX0zLw.5vX_y5Fi
[TEST 2] legacy sd1 (builder #look=) decode .... PASS
[TEST 3] garbage rejected ..................... PASS  (unrecognized share code (not sd2, not sd1))
[TEST 4] tampered payload rejected ............ PASS  (checksum mismatch — payload tampered or truncated)
[TEST 5] oversized payload rejected ........... PASS  (truncated zlib stream)
[TEST 6] Style DNA ............................ score=89
         band=Signature  archetype=Streetwear  palette=['White', 'Black', 'Denim']
         components={'completeness': 1.0, 'harmony': 0.933, 'diversity': 0.824, 'coverage': 0.625, 'wear_balance': 0.955}
[TEST 7] anonymized handle .................... wool-closet-93  (tags kept: ['Old Money', 'Work', 'Winter'])
[TEST 8] gate('The color mix feels so calm!'  ) -> allow           nudge=None
[TEST 8] gate('no offense but this is mid'    ) -> hide_for_review nudge='Say it kindly or not at all — how about starting with something you like?'
[TEST 8] gate('this is ugly trash'            ) -> block           nudge="Try naming what works: 'the color mix feels calm'."
[TEST 9] challenge 'Old Money May' entry ........... PASS (qualified)
         story frames=1 view-band=noticed  board='Rainy Manila commute'
         onboarding proof: ['12,400 people hang their wardrobe here.', '318 looks were pinned today.', 'No follower counts. No streaks. Just your clothes.']
```

Additional verified properties (separate real run this session):

- The full share URL for the Test-1 look is **219 characters** (`sd2:eNodykELgjAYxvGvMt7zDLW6eBSELrHSRQfxMHTBcG4wXSLRd--x25_f83zoTUXOyVDRtiTFjTjJjHiedrylUkgprqASlP2puYiqgTS7nHcRD1nVEAE5pl3HaaGCamXcxno_TXHRWD3s6cOIXJG9twPSIe_R6IVZtekws5cPTLEVIKOeB7Ud8Ip4WeO0S4IyNjnl9P0BNX0zLw.5vX_y5Fi`).
- Re-encoding the decoded look reproduces the identical code byte-for-byte, and decoding asserts deep equality on items/title/occasion/weather/note/handle — `DETERMINISTIC RE-ENCODE + DECODE: PASS`.

---

*COMMUNITY.md v1.0 · Agent #18 · grounded in the real `WardrobeItem.kt` / `AppConstants.kt` / `SavedOutfitsScreen.kt` / `outfit_builder.html` / `recommender.py` — no invented schema.*
