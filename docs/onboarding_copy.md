# onboarding_copy.md — styledrop first-run, every word

**Voice:** sartorial · precise · warm. The app speaks like a tailor who knows your name.
**Rules:** sentence case everywhere including buttons · never ALL CAPS · zero exclamation points in this flow · numbers as numerals · max two type families per screen.
**Source of truth:** this file mirrors `OnboardingContent` in `OnboardingScreens.kt` 1:1. Change one, change both.

---

## 0 · Do / Don't — the difference the voice makes

| Moment | ❌ Generic app voice | ✅ styledrop voice |
|---|---|---|
| Welcome | "Let's set up your profile!" | "Let's take your measurements." |
| Consent | "Allow access to your photos" | "Only if you'd rather pull an existing picture." |
| Empty state | "No items found. Add garments to get started." | "Your atelier is waiting. Hang your first piece." |
| Quiz prompt | "Select your preferred style" | "Pick the look you'd wear to brunch." |
| Quiz skip | "Skip this question" | "Skip the rest" |
| Tutorial | "Drag and drop the image" | "Drag the photo onto the hanger. That's the whole trick." |
| Success | "Profile created successfully! 🎉" | "Here's your fitting profile." |
| Permission denied | "Permission denied." | "Not now" |
| Error | "Error 500: something went wrong" | "A loose thread. Give it a moment and try again." |
| Deletion | "Are you sure you want to delete?" | "Off the rail? This piece leaves your atelier for good." |

**Lexicon.** We say *piece · capsule · rail · atelier · fitting · drop · hang it in · pinned for you*. We never say *garment · inventory · closet dump · outfit generator · AI-powered result · upload · delete*.

---

## 1 · Welcome screen

| Element | String |
|---|---|
| Eyebrow | Your atelier, in your pocket |
| Title | Let's take your measurements. |
| Body | No tape measure. Just five quick questions, then a short fitting quiz so we know what you actually reach for. Everything stays on this phone. |
| Rail chips | 5 declarations · 12 quiz looks · your rail, your rules |
| Primary CTA | Begin the fitting |
| Secondary CTA | Skip — start me on defaults |

---

## 2 · Declaration 1 — gender lens

**Eyebrow:** Declaration 1 of 4
**Title:** Who are we cutting for?
**Body:** This only sets how garments are named and sized. You can change it later.
**CTA:** Continue

| Option | Caption |
|---|---|
| 👚 Women's fit | Cut, drape and sizing for a women's line. |
| 👔 Men's fit | Cut, drape and sizing for a men's line. |
| 🧵 Show me everything | Both rails, no filter. Some of the best looks cross over. |
| ○ Rather not say | We'll cut for an open, unisex fitting. |

---

## 3 · Declaration 2 — climate

**Eyebrow:** Declaration 2 of 4
**Title:** What does your weather do?
**Body:** We read the sky before we read the rail.
**CTA:** Continue
**Rail chip:** Hemisphere decides the season

| Option | Caption |
|---|---|
| 🌴 Hot all year | Tropical — heat, humidity, sudden rain. |
| 🌤 Warm, mild winters | Light layers carry you through January. |
| 🍂 Four real seasons | A coat, a tee and everything between. |
| ❄️ Long, hard winters | Outerwear is the headline, not an afterthought. |

**Hemisphere toggle:** Northern hemisphere · Southern hemisphere

---

## 4 · Declaration 3 — lifestyle (multi-select)

**Eyebrow:** Declaration 3 of 4
**Title:** Where does a normal week take you?
**Body:** Choose everything that applies.
**CTA:** Continue
**Counter (empty):** Pick at least one — the week has to go somewhere.
**Counter (filled):** {n} selected

| Option | Caption |
|---|---|
| 🎒 Student | Lectures, campus, a lot of walking. |
| 💼 Office & meetings | Rooms that expect a collar. |
| 🎨 Creative studio | Where the brief is loose and the look is not. |
| 🏡 Working from home | Comfort that still holds a line. |
| 👟 On my feet all day | Shifts, studios, sites. Shoes matter most. |
| ✈️ Travel often | One carry-on, five climates. |
| 🥂 Social & events | Dinners, parties, openings. |
| 🏃 Gym & outdoors | Movement first, style second. |

---

## 5 · Declaration 4 — goals (multi-select)

**Eyebrow:** Declaration 4 of 4
**Title:** What should this wardrobe fix?
**Body:** Pick the ones that matter. We'll hold you to them gently.
**CTA:** To the fitting

| Option | Caption |
|---|---|
| ♻️ Dress better with what I own | The rail is fuller than the rotation. |
| 🧷 Buy less, choose better | Fewer pieces, sharper decisions. |
| 📐 Build a capsule | A line of pieces that all speak to each other. |
| 🔀 Stop repeating outfits | Same rail, new combinations. |
| ✒️ Find my signature | One look that arrives before you do. |
| 📎 Look sharper at work | Confidence with a collar on it. |
| 🧳 Pack lighter | A carry-on that dresses you for a week. |
| 🔁 Wear what I forget | Bring the back of the rail forward. |

---

## 6 · The fitting quiz — 12 questions, 48 looks

**Counter:** {n} of 12 · **Skip:** Skip the rest · **CTA:** Next look → *See my fitting* (last)
**Unanswered hint:** Nothing picked yet — go with your first instinct.
**Answered hint:** Pinned. Change it any time before the next question.

### Q1 · Sunday, 11 a.m.
**Prompt:** Pick the look you'd wear to brunch.
**Helper:** The table is outside. The coffee is good. Go with your gut.

| Look | Caption |
|---|---|
| Soft and easy | Washed linen shirt, wide trousers, clean white sneakers. |
| Put together | Knitted polo, tailored chinos, suede loafers. |
| Loud on purpose | Oversized graphic tee, baggy denim, chunky trainers. |
| Quietly different | Cropped technical jacket, wide pleated trousers, minimal runners. |

### Q2 · Monday, 9 a.m.
**Prompt:** What are you wearing into the office?
**Helper:** However your week actually looks.

| Look | Caption |
|---|---|
| A full suit, and it fits | Shoulders clean, trousers breaking once. |
| Soft tailoring | Unstructured blazer, knit, wide trousers. |
| Clean and unfussy | Fine knit, straight leg, plain leather shoes. |
| Nobody dressed me | Heavy hoodie, cargo trousers, the good trainers. |

### Q3 · Saturday, 8 p.m.
**Prompt:** A friend's birthday dinner. Your move.
**Helper:** Somewhere with low light and a long menu.

| Look | Caption |
|---|---|
| All black, all business | Slim roll-neck, dark trousers, sharp boots. |
| Silk somewhere | Slip dress or open shirt, heels or loafers. |
| Denim and a good jacket | Selvedge denim, leather jacket, boots. |
| Whatever's loudest | Metallic top, straight jeans, platform shoes. |

### Q4 · Two free days
**Prompt:** Which weekend uniform feels right?
**Helper:** No plans, no audience.

| Look | Caption |
|---|---|
| Sweats and good socks | Matching set, heavy cotton, worn trainers. |
| Denim on denim | Chore jacket, straight jeans, cap. |
| Technical and tidy | Shell jacket, tapered cargos, trail shoes. |
| Ribbed knit and wide denim | Knit vest, high-waist, small shoulder bag. |

### Q5 · First cold morning
**Prompt:** Which coat leaves the rail?
**Helper:** One coat, the whole season.

| Look | Caption |
|---|---|
| Wool overcoat | Camel, knee length, belted or not. |
| Puffer, properly big | Matte shell, high collar, functional. |
| Leather, broken in | Moto cut, worn edges, ages well. |
| Hooded parka, quiet colour | Olive or stone, relaxed shoulder. |

### Q6 · One pair, six months
**Prompt:** Choose carefully. These will be seen daily.
**Helper:** Comfort is part of the look.

| Look | Caption |
|---|---|
| Clean white leather | Low profile, no logo, wiped weekly. |
| Suede loafers | Brown or tan, worn with everything. |
| Chunky trainers | Statement sole, colour in the details. |
| Leather boots | Chelsea or lace-up, a little scuffed. |

### Q7 · Colour
**Prompt:** Pick the palette that feels like you.
**Helper:** This sets the default filter across your whole rail.

| Look | Caption |
|---|---|
| Ink and nothing else | Black, charcoal, off-white. Never a loud idea. |
| Ivory and sand | Cream, beige, oatmeal. Soft and expensive-looking. |
| Denim and olive | Washed indigo, field green, worn brown. |
| One accent, worn hard | A neutral base with a single red or burgundy. |

### Q8 · Silhouette
**Prompt:** How do you like your clothes to sit?
**Helper:** Keys are AppConstants.fits — this feeds every future suggestion.

| Look | Caption |
|---|---|
| Oversized | Volume on purpose, shoulders dropped. |
| Slim | Close to the body, clean line. |
| Regular | Exactly what the label says. |
| Relaxed | Room to move, nothing sloppy. |

### Q9 · Surface
**Prompt:** Choose the surface you'd wear most.
**Helper:** Keys are AppConstants.patterns.

| Look | Caption |
|---|---|
| Plain | Texture does the talking, not print. |
| Striped | Pinstripe or breton, a classic two-way. |
| Graphic | Type, art, a whole statement on the chest. |
| Checked or camo | A pattern that commits. |

### Q10 · Casting
**Prompt:** Which of these could be a still from your wardrobe?
**Helper:** Style families. Pick the frame you'd live in.

| Look | Caption |
|---|---|
| Streetwear | Sneakers, hoodie, low-slung denim, attitude. |
| Minimalist | Few pieces, exact proportions, no ornament. |
| Old Money | Camel, navy, cashmere, quiet wealth. |
| Korean | Soft volume, pastel knits, clean lines. |

### Q11 · Five days, one carry-on
**Prompt:** What goes in the bag?
**Helper:** This is how we learn to build a capsule for you.

| Look | Caption |
|---|---|
| One colour story | Three tops, two trousers, all interchangeable. |
| One good jacket and tees | The jacket does the work. Everything else is plain. |
| Technical everything | Packable shell, quick-dry layers, trail shoes. |
| Photos over practicality | Loud shirts, sunglasses, whatever fits the feed. |

### Q12 · Last one
**Prompt:** Someone describes you to a friend. Which line do you hope they say?
**Helper:** Your answer shapes the tone of every styling note we write.

| Look | Caption |
|---|---|
| "Always looks expensive." | Quiet, exact, considered. |
| "Effortless, never tries." | Simple pieces, perfect proportions. |
| "Always the best-dressed one." | Risk, colour, presence. |
| "Very now." | Trends, worn with confidence. |

---

## 7 · Profile reveal

| Element | String |
|---|---|
| Eyebrow | Your fitting profile |
| Title | Here's your fitting profile. |
| Body | Read the profile. If a line feels wrong, retake any answer. |
| Card label | Your line |
| Headline | {topStyle1} · {topStyle2} · {topStyle3} |
| Palette label | Palette |
| Formality label | Formality |
| Formality values | Relaxed · Balanced · Sharp |
| Measurements label | The measurements |
| Measurements chips | fit: {genderLens} · climate: {climate} · {hemisphere}ern hemisphere · week: {lifestyle} · goals: {goals} |
| Footer line | {lifestyle} — every suggestion will be filtered through this. |
| Primary CTA | Teach me to hang a photo |
| Secondary CTA | Retake the fitting quiz |
| Empty headline | Still being cut |

---

## 8 · Photo tutorial

| Element | String |
|---|---|
| Eyebrow | The fitting, hands-on |
| Title | Hang your first photo. |
| Body | Drag the photo onto the hanger. That's the whole trick — good light, plain background, garment flat. |
| Drop-zone chip | drag me up onto the hanger |
| Hint — not started | Drag the photo up onto the hanger |
| Hint — missed once | Closer. Aim for the hanger's crossbar. |
| Hint — landed | Hung. That's exactly how every piece goes in. |
| Tap fallback | Or tap it, if dragging isn't your thing. |
| Card label | your photo |
| Card a11y label | Sample garment photo. Drag it up onto the hanger, or double-tap to hang it. |

**Framing rules** (shipped as copy, not a tooltip):
1. Plain background — a wall, a bed sheet, a door.
2. Good light on the front. Never a flash.
3. Garment flat or on the hanger, cuffs showing.
4. One piece per photo. We'll do the sorting.

**CTA — not landed:** Hang it for me · **CTA — landed:** That's the trick — next · **Secondary:** Use a real photo instead

---

## 9 · First-item tutorial + sample rail

| Element | String |
|---|---|
| Eyebrow | The rail, filled |
| Title | Now a real one. |
| Body | We've hung ten starter pieces on your rail so nothing feels empty. Keep them, or take them off once yours are in. |
| Status chip | {n} starter pieces · on your rail / not yet hung |
| Toggle CTA | Keep the starters / Remove the starters |
| Footnote | Tap any piece to see why we hung it. They're marked "StyleDrop Starter" so you can remove them in one go later. |
| Section title | Now the house rules. |
| Rule 1 | One piece per photo. We sort the rest. |
| Rule 2 | Only use the pieces you actually own — that's what makes the suggestions honest. |
| Rule 3 | Rate a look after wearing it. The stylist learns from the rating, not from guesses. |
| Primary CTA | Open my atelier |

### The ten starter pieces — the lesson is the caption

| Piece | Metadata line | Why we hung it |
|---|---|---|
| Oversized Cotton T-Shirt | 👕 Tops · White · Oversized | The plain top every look is built on. |
| Merino Knit | 👕 Tops · Navy · Regular | One knit turns a t-shirt and jeans into something with an occasion. |
| Straight-Leg Denim | 👖 Bottoms · Navy · Relaxed | Straight, not skinny. It sits with everything above it. |
| Pleated Trouser | 👖 Bottoms · Beige · Relaxed | Pleats give you an evening you didn't have to plan. |
| Low Leather Sneaker | 👟 Shoes · White · Regular | Clean white carries a full outfit on its own. |
| Suede Loafer | 👟 Shoes · Brown · Regular | The one upgrade that reads as "took an interest". |
| Wool Overcoat | 🧥 Outerwear · Beige · Relaxed | Camel over a plain base is the whole quiet-luxury idea. |
| Bomber Jacket | 🧥 Outerwear · Black · Regular | The casual answer when the coat feels like too much. |
| Leather Belt | 🧢 Accessories · Brown · Regular | Match it to the shoe and the outfit suddenly agrees with itself. |
| Wool Cap | 🧢 Accessories · Olive · Regular | The cheapest way to make a plain look intentional. |

---

## 10 · Permissions

| Element | String |
|---|---|
| Eyebrow | Finishing touches |
| Title | Before the finish |
| Body | Three permissions. Each one asked once, each one explained, and none of them required. |
| Skip | Not now — finish |
| Rail chips | asked once · at the moment of use · never in the background |
| CTA | Open my atelier |

**Camera** — So you can photograph a piece straight onto the hanger instead of hunting for an old photo.
**Photos** — Only if you'd rather pull an existing picture. The Android photo picker lets you share one image without handing over the library.
**Location** — Reads the local weather so a suggestion isn't a coat in a heatwave. Coarse only — never a precise fix. You can set the city by hand instead.

**Rationale card body (every permission):** We only need this the moment you use the feature — never in the background. You can say no and still use everything else.
**Rationale CTAs:** Got it — ask me · Not now

**Status labels:** Allowed · Not asked · Not now · Off in system settings · Not needed here
**API 33+ photos note:** Picker needs no permission on this Android version
**Denied-forever CTA:** Open settings

---

## 11 · Skip paths

| Trigger | Toast / result copy |
|---|---|
| "Skip — start me on defaults" | Rails set to Minimalist · Casual · Smart Casual. Adjust any time in Profile. |
| "Skip the rest" (quiz) | Keeps every answer already given; goes to the photo tutorial. |
| "Not now — finish" (permissions) | Finishes with zero permissions granted. Every feature degrades gracefully. |
| Profile → Redo the fitting | Resumes at the last answer rather than restarting. |

**Default profile produced by skip:** Minimalist / Casual / Smart Casual · palette Black, White, Navy · formality Balanced · lifestyle office · goal wearmore · `seeded_from_skip = true`.

---

## 12 · Error and edge-case copy

| Situation | String |
|---|---|
| Generic failure | A loose thread. Give it a moment and try again. |
| Empty wardrobe (existing app string) | Your atelier is waiting. Hang your first piece. |
| Looking sharper on the cold-weather branch | The sky turned. The trench agrees. |
| Wear tracking | Three wears this season — a quiet favorite. |
| Nothing selected on a required step | Pick at least one — the week has to go somewhere. |
| Nothing picked on a quiz question | Nothing picked yet — go with your first instinct. |
| Seed already installed | (toggle reads) Remove the starters |
| Wardrobe missing a trend (Agent #10 handoff) | Not present in your wardrobe — worth a look. |

---

## 13 · What is deliberately absent

- **No exclamation points.** Zero, across all 7 screens.
- **No ALL CAPS.** Not in buttons, not in eyebrows, not in chips.
- **No hype words.** No *amazing*, *awesome*, *supercharge*, *unleash*.
- **No gamification.** No streaks, no confetti, no progress percentage as a score.
- **No emoji in prose.** Emoji appear only as an option's own glyph on a tile, never inside a sentence.
- **No permissions at launch.** Not one asks before the user has seen a reason.
- **No "AI-powered" anywhere.** The stylist is *pinned for you*, not *generated*.
