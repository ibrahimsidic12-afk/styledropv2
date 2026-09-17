#!/usr/bin/env python3
"""styledropv2 — community sharing module (Agent #18: COMMUNITY + SHARING).

Grounded in the real codebase (clone of github.com/ibrahimsidic12-afk/styledropv2):

* ``WardrobeItem`` (models/WardrobeItem.kt) — Room entity `wardrobe_items`
  (id, imageUrl, category, type, color, secondaryColor, style, fit, pattern,
  season, brand, timesWorn, isFavoriteItem, lastWorn, createdAt).
* ``ItemCategory`` — Tops/Bottoms/Shoes/Outerwear/Accessories/Bags/Watches/Jewelry.
* ``AppConstants`` — occasions, styles, colorPreferences, patterns, fits, seasons.
* ``outfit_builder.html`` share format (Agent #05): ``#look=`` + base64 of
  ``JSON.stringify([[slotId, itemId, z], ...])``  →  this module calls that
  wire version **sd1** and keeps full decode compatibility with it.
* ``recommender.py`` (Agent #08) — ``Context`` / ``OutfitLog`` / ``recommend()``
  power the Style DNA score. Imported locally, no path hacking needed when this
  file sits next to it (see ``_import_recommender``).

Wire versions
-------------
``sd1``  legacy builder format: bare base64( JSON [[slot, itemId, z], ...] ).
         No checksum, may contain ``+/`` and ``=``.
``sd2``  this module's format:
         ``sd2:`` + ``<base64url(zlib(json))-no-padding>`` + ``.`` +
         ``<8-char checksum>`` where checksum = base64url( sha256(SALT + body)[:6] ).
         Compact keys keep URLs short; zlib keeps them shorter; the checksum
         catches truncation/tampering; a hard size cap stops zip bombs.

Privacy-first rules baked into the data classes (see COMMUNITY.md §3):
  * no public like/reaction counts — only coarse "resonance bands",
  * no follower counts, no streaks, no public leaderboards,
  * every cross-user surface is pseudonymous (deterministic handle + salt),
  * comments pass a moderation gate; blocked posts get a kind-reply nudge.

Pure standard library. Run ``python3 sharing.py`` for the round-trip demo.
"""
from __future__ import annotations

import base64
import hashlib
import importlib.util
import json
import os
import sys
import zlib
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

# ============================================================================
# 0. CONSTANTS — mirrored with Sharing.kt (Android side)
# ============================================================================

WIRE_PREFIX = "sd2:"
CHECKSUM_SEP = "."
SIG_SALT = "styledrop::v2"          # keep in sync with Sharing.kt
MAX_COMPRESSED_BYTES = 4096         # shareable link, not a file host
MAX_DECOMPRESSED_BYTES = 8192       # anti zip-bomb cap
WIRE_VERSION = 2

# builder slots (outfit_builder.html SLOTS) ↔ ItemCategory (WardrobeItem.kt)
SLOT_CATEGORY: Dict[str, str] = {
    "TOP": "Tops", "BOTTOM": "Bottoms", "SHOES": "Shoes", "OUTER": "Outerwear",
    "HAT": "Accessories", "BAG": "Bags", "WATCH": "Watches", "JEWELRY": "Jewelry",
}
CATEGORY_SLOT: Dict[str, str] = {v: k for k, v in SLOT_CATEGORY.items()}

# AppConstants.kt vocabularies — tags/challenges may only use these
STYLES = ["Streetwear", "Casual", "Minimalist", "Old Money", "Y2K", "Korean",
          "Smart Casual", "Formal", "Sporty", "Vintage", "Techwear", "Grunge"]
OCCASIONS = ["School", "College", "Work", "Casual", "Date", "Party",
             "Wedding", "Gym", "Travel", "Beach", "Formal"]
SEASONS = ["All Season", "Summer", "Winter", "Spring/Fall"]

# theme tokens (ui/theme/Color.kt) allowed as story backgrounds
STORY_BG_TOKENS = ["ivory", "linen", "canvas", "gold", "ink", "felt", "mulled"]


class ShareDecodeError(ValueError):
    """Raised for malformed / tampered / oversized share payloads."""


# ============================================================================
# 1. WIRE CODEC — encode/decode for sd2 (+ legacy sd1 read support)
# ============================================================================

def _b64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def _b64url_decode(text: str) -> bytes:
    pad = "=" * (-len(text) % 4)
    try:
        return base64.urlsafe_b64decode(text + pad)
    except Exception as exc:                                   # binascii.Error
        raise ShareDecodeError("payload is not valid base64url") from exc


def _checksum(body: str) -> str:
    digest = hashlib.sha256((SIG_SALT + body).encode("utf-8")).digest()
    return _b64url_encode(digest[:6])[:8]


def encode_share_code(items: List[Tuple[str, str, int]],
                      *, title: str = "", occasion: str = "",
                      weather: str = "", note: str = "",
                      created: Optional[int] = None,
                      handle: str = "") -> str:
    """Encode a look as ``sd2`` wire string.

    ``items``  — ``[(slotId, itemId, z), ...]`` (same shape as the builder's
                 ``encodeLook()`` payload; slotIds are the 8 builder slots).
    Everything else is optional metadata carried in the URL itself so a shared
    link restores the full look without any server round-trip.
    """
    if not items:
        raise ShareDecodeError("cannot encode an empty look")
    if len(items) > len(SLOT_CATEGORY):
        raise ShareDecodeError("too many items for one look")
    payload: Dict[str, Any] = {"v": WIRE_VERSION,
                               "i": [[s, i, int(z)] for s, i, z in items]}
    if title:
        payload["t"] = title[:64]
    if occasion:
        payload["o"] = occasion[:24]
    if weather:
        payload["w"] = weather[:8]
    if note:
        payload["n"] = note[:140]
    if created is not None:
        payload["c"] = int(created)
    if handle:
        payload["u"] = handle[:32]
    raw = json.dumps(payload, separators=(",", ":"), ensure_ascii=True).encode("utf-8")
    if len(raw) > MAX_DECOMPRESSED_BYTES:
        raise ShareDecodeError("look payload too large")
    body = _b64url_encode(zlib.compress(raw, 9))
    return f"{WIRE_PREFIX}{body}{CHECKSUM_SEP}{_checksum(body)}"


def decode_share_code(code: str) -> Dict[str, Any]:
    """Decode ``sd2`` (or legacy ``sd1``) into a normalized dict.

    Returns ``{"version", "items": [(slot, itemId, z)], "title", "occasion",
    "weather", "note", "created", "handle", "raw_payload"}``.
    Raises ``ShareDecodeError`` on anything malformed, tampered or oversized.
    """
    if not isinstance(code, str) or not code.strip():
        raise ShareDecodeError("empty share code")
    code = code.strip()

    if code.startswith(WIRE_PREFIX):
        body_and_sig = code[len(WIRE_PREFIX):]
        if CHECKSUM_SEP not in body_and_sig:
            raise ShareDecodeError("missing checksum separator")
        body, _, sig = body_and_sig.rpartition(CHECKSUM_SEP)
        if len(sig) != 8 or not _constant_eq(_checksum(body), sig):
            raise ShareDecodeError("checksum mismatch — payload tampered or truncated")
        compressed = _b64url_decode(body)
        if len(compressed) > MAX_COMPRESSED_BYTES:
            raise ShareDecodeError("compressed payload exceeds size cap")
        raw = _safe_zlib_decompress(compressed)
        try:
            payload = json.loads(raw.decode("utf-8"))
        except Exception as exc:
            raise ShareDecodeError("decompressed payload is not valid JSON") from exc
        if not isinstance(payload, dict) or payload.get("v") != WIRE_VERSION:
            raise ShareDecodeError("unsupported wire version")
        items = _validate_items(payload.get("i"))
        return {"version": WIRE_VERSION, "items": items,
                "title": str(payload.get("t", ""))[:64],
                "occasion": str(payload.get("o", ""))[:24],
                "weather": str(payload.get("w", ""))[:8],
                "note": str(payload.get("n", ""))[:140],
                "created": payload.get("c"),
                "handle": str(payload.get("u", ""))[:32],
                "raw_payload": payload}

    # ---- legacy sd1: bare btoa(JSON.stringify([[slot, itemId, z], ...])) ----
    try:
        pad = "=" * (-len(code) % 4)
        raw = base64.b64decode(code + pad).decode("utf-8")
        payload = json.loads(raw)
    except Exception as exc:
        raise ShareDecodeError("unrecognized share code (not sd2, not sd1)") from exc
    if not isinstance(payload, list):
        raise ShareDecodeError("legacy payload must be a list")
    items = _validate_items(payload, legacy_shape=True)
    return {"version": 1, "items": items, "title": "", "occasion": "",
            "weather": "", "note": "", "created": None, "handle": "",
            "raw_payload": payload}


def _safe_zlib_decompress(compressed: bytes) -> bytes:
    out = zlib.decompressobj()
    chunks: List[bytes] = []
    size = 0
    for chunk in (compressed[i:i + 512] for i in range(0, len(compressed), 512)):
        try:
            piece = out.decompress(chunk, MAX_DECOMPRESSED_BYTES)
        except zlib.error as exc:
            raise ShareDecodeError("corrupt zlib stream") from exc
        chunks.append(piece)
        size += len(piece)
        if size > MAX_DECOMPRESSED_BYTES:
            raise ShareDecodeError("decompressed payload exceeds size cap (zip bomb?)")
    if not out.eof:
        raise ShareDecodeError("truncated zlib stream")
    return b"".join(chunks)


def _validate_items(raw_items: Any, legacy_shape: bool = False
                    ) -> List[Tuple[str, str, int]]:
    if not isinstance(raw_items, list) or not raw_items:
        raise ShareDecodeError("look has no items")
    if len(raw_items) > len(SLOT_CATEGORY):
        raise ShareDecodeError("too many items for one look")
    seen_slots = set()
    out: List[Tuple[str, str, int]] = []
    for entry in raw_items:
        if not isinstance(entry, (list, tuple)) or len(entry) != 3:
            raise ShareDecodeError("item entry must be [slot, itemId, z]")
        slot, item_id, z = entry
        if slot not in SLOT_CATEGORY:
            raise ShareDecodeError(f"unknown slot: {slot!r}")
        if slot in seen_slots:
            raise ShareDecodeError(f"duplicate slot: {slot!r}")
        seen_slots.add(slot)
        if not isinstance(item_id, str) or not item_id or len(item_id) > 64:
            raise ShareDecodeError("invalid itemId")
        if isinstance(z, bool) or not isinstance(z, int):
            z = int(z) if isinstance(z, (int, float)) else 0
        out.append((slot, item_id, int(z)))
    return out


def _constant_eq(a: str, b: str) -> bool:
    return hashlib.sha256(a.encode()).digest() == hashlib.sha256(b.encode()).digest()


def build_share_url(share_code: str, base_url: str = "https://styledrop.app/builder") -> str:
    """``#look=`` URL — same fragment the builder decodes on boot."""
    return f"{base_url}#look={share_code}"


# ============================================================================
# 2. SHARED LOOK OBJECT — the thing every community surface exchanges
# ============================================================================

@dataclass
class SharedLook:
    """A portable look. ``items`` uses (slot, itemId, z) triples; the payload
    references the SHARER's local item ids — the receiver is offered
    'remix with my wardrobe', never given the sharer's private data."""
    items: List[Tuple[str, str, int]]
    title: str = ""
    occasion: str = ""
    weather: str = ""
    note: str = ""
    handle: str = ""

    def to_share_code(self) -> str:
        return encode_share_code(self.items, title=self.title,
                                 occasion=self.occasion, weather=self.weather,
                                 note=self.note, handle=self.handle)

    @classmethod
    def from_share_code(cls, code: str) -> "SharedLook":
        d = decode_share_code(code)
        return cls(items=[tuple(i) for i in d["items"]], title=d["title"],
                   occasion=d["occasion"], weather=d["weather"],
                   note=d["note"], handle=d["handle"])

    def to_dict(self) -> Dict[str, Any]:
        return {"items": [list(i) for i in self.items], "title": self.title,
                "occasion": self.occasion, "weather": self.weather,
                "note": self.note, "handle": self.handle}


# ============================================================================
# 3. ANONYMIZATION — pseudonymous handles, zero user ids on any wire
# ============================================================================

_HANDLE_A = ["quiet", "silk", "linen", "olive", "ivory", "denim", "amber",
             "wool", "flax", "cedar", "coastal", "vault"]
_HANDLE_B = ["cotton", "tailor", "rail", "stitch", "hem", "loft", "seam",
             "bobbin", "pattern", "closet", "pin", "label"]


def anonymize_handle(user_id: str, salt: str) -> str:
    """Deterministic pseudonym, e.g. ``linen-rail-42``.

    Same user + same salt ⇒ same handle (stable identity inside one community),
    but the handle can never be reversed to the account, and rotating the salt
    re-anonymizes the entire feed at once. The 2-digit suffix only widens the
    name space; it is not a user id.
    """
    digest = hashlib.sha256((salt + "|" + user_id).encode("utf-8")).digest()
    return (f"{_HANDLE_A[digest[0] % len(_HANDLE_A)]}-"
            f"{_HANDLE_B[digest[1] % len(_HANDLE_B)]}-"
            f"{digest[2] % 100:02d}")


def anonymize_wardrobe_for_feed(items: List[Dict[str, Any]],
                                salt: str) -> List[Dict[str, Any]]:
    """Strip EVERY identifying field from wardrobe rows before they can enter
    the inspiration feed. Removes: imageUrl (could contain faces/backgrounds),
    brand (purchasable fingerprint), timestamps (behavioural fingerprint).
    Keeps only the style vocabulary the app already exposes publicly."""
    safe = []
    for it in items:
        safe.append({
            "category": it.get("category", ""),
            "type": it.get("type", ""),
            "color": it.get("color", ""),
            "secondaryColor": it.get("secondaryColor", ""),
            "style": it.get("style", ""),
            "fit": it.get("fit", ""),
            "pattern": it.get("pattern", ""),
            "season": it.get("season", "All Season"),
        })
    return safe


@dataclass
class FeedItem:
    """One anonymized community wardrobe card (inspiration feed)."""
    feed_id: str
    handle: str                    # pseudonym — never the account id
    looks: List[str]               # sd2 share codes (self-contained, no server)
    tags: List[str]                # restricted to AppConstants vocab
    resonance: str = "gentle"      # gentle|warm|beloved — bands, NOT counts
    created_day: str = ""          # date only, no time (anti-profiling)

    def to_dict(self) -> Dict[str, Any]:
        return {"feed_id": self.feed_id, "handle": self.handle,
                "looks": self.looks, "tags": self.tags,
                "resonance": self.resonance, "created_day": self.created_day}

    @staticmethod
    def validate_tags(tags: List[str]) -> List[str]:
        allowed = set(STYLES) | set(OCCASIONS) | set(SEASONS)
        return [t for t in tags if t in allowed]


# ============================================================================
# 4. STYLE STORIES — 24h looks, swipe-up, no view counts per viewer
# ============================================================================

@dataclass
class StoryFrame:
    look_code: str                 # sd2 code — frame IS a look, not a selfie
    caption: str = ""
    bg_token: str = "ivory"        # one of STORY_BG_TOKENS

    def __post_init__(self) -> None:
        if self.bg_token not in STORY_BG_TOKENS:
            raise ValueError(f"bg_token must be one of {STORY_BG_TOKENS}")
        decode_share_code(self.look_code)          # fail fast on bad codes


@dataclass
class StyleStory:
    handle: str
    frames: List[StoryFrame]
    expires_hours: int = 24        # hard expiry — stories self-destruct
    reply_mode: str = "kind"       # only pre-tuned kind reactions allowed

    def __post_init__(self) -> None:
        if not 1 <= len(self.frames) <= 5:
            raise ValueError("a story carries 1–5 frames")
        if self.reply_mode not in ("kind",):
            raise ValueError("reply_mode must stay 'kind' — no free-text DMs")

    def to_dict(self) -> Dict[str, Any]:
        return {"handle": self.handle,
                "frames": [{"look_code": f.look_code, "caption": f.caption,
                            "bg_token": f.bg_token} for f in self.frames],
                "expires_hours": self.expires_hours,
                "reply_mode": self.reply_mode}


# viewer activity is recorded ONLY as an aggregate band (never per-viewer rows)
def story_view_band(total_views: int) -> str:
    if total_views < 10:
        return "quiet"
    if total_views < 50:
        return "noticed"
    return "circulating"


# ============================================================================
# 5. STYLE CHALLENGES — solo, vocab-locked, no leaderboards
# ============================================================================

@dataclass
class StyleChallenge:
    challenge_id: str
    name: str                      # e.g. "Old Money May"
    hashtag: str                   # e.g. "#OldMoneyMay"
    theme_style: str               # MUST be one of AppConstants.styles
    starts: str                    # ISO date
    ends: str
    rules: List[str] = field(default_factory=list)

    def __post_init__(self) -> None:
        if self.theme_style not in STYLES:
            raise ValueError(f"theme_style must be one of AppConstants.styles: {STYLES}")

    def to_dict(self) -> Dict[str, Any]:
        return {"challenge_id": self.challenge_id, "name": self.name,
                "hashtag": self.hashtag, "theme_style": self.theme_style,
                "starts": self.starts, "ends": self.ends, "rules": self.rules}

    def entry_is_valid(self, look: SharedLook, wardrobe_styles: set) -> Tuple[bool, str]:
        """An entry qualifies when the tagged style is actually present in the
        sharer's own items — self-reported, checked locally, never ranked."""
        if self.theme_style not in wardrobe_styles:
            return False, f"add a '{self.theme_style}' piece from your own wardrobe first"
        if not look.items:
            return False, "look is empty"
        return True, "qualified"

    def showcase_policy(self) -> Dict[str, Any]:
        return {"mode": "curated rotation",   # not a top-N list
                "ranking": None,              # no leaderboard, ever
                "refresh": "daily shuffle, every qualifying look appears at least once",
                "counts_visible": False}


def default_challenges(today: str = "2026-09-17") -> List[StyleChallenge]:
    return [
        StyleChallenge("ch-omm-05", "Old Money May", "#OldMoneyMay",
                       "Old Money", "2026-05-01", "2026-05-31",
                       ["wear pieces you already own", "one look per day max"]),
        StyleChallenge("ch-sws-09", "Streetwear Sunday", "#StreetwearSunday",
                       "Streetwear", "2026-09-06", "2026-12-27",
                       ["every Sunday", "remix a friend's look with your pieces"]),
        StyleChallenge("ch-min-09", "Minimalist September", "#MinimalistSeptember",
                       "Minimalist", "2026-09-01", "2026-09-30",
                       ["max 3 colors per look", "Plain patterns only"]),
    ]


# ============================================================================
# 6. FRIENDS + COMMENTS — consent first, gate the tone, cap the volume
# ============================================================================

@dataclass
class FriendEdge:
    handle: str                    # pseudonym of the friend
    status: str = "pending"        # pending|accepted — mutual consent required
    since: str = ""

    def __post_init__(self) -> None:
        if self.status not in ("pending", "accepted"):
            raise ValueError("status must be pending|accepted")


# --- moderation gate: two tiers, zero punishment theater --------------------
_BLOCK_TERMS = ["ugly", "trash", "garbage", "loser", "stupid", "idiot",
                "shut up", "kill yourself", "kys", "worthless", "pathetic",
                "hate you", "nobody likes you", "fat", "freak"]
_FLAG_TERMS = ["no offense", "just kidding", "ratio", "cope", "mid",
               "downgrade", "weird flex", "try harder"]


@dataclass
class GateResult:
    action: str                    # allow | hide_for_review | block
    reason: str
    nudge: Optional[str] = None    # kind alternative offered to the writer


def comment_gate(text: str) -> GateResult:
    """Pre-post moderation. Blocking is quiet: the writer sees their comment
    'posted', the recipient never does — no public rejection, no drama loop."""
    t = " " + " ".join(text.lower().split()) + " "
    for term in _BLOCK_TERMS:
        if term in t:
            return GateResult("block", f"blocked term: {term!r}",
                              "Try naming what works: 'the color mix feels calm'.")
    for term in _FLAG_TERMS:
        if term in t:
            return GateResult("hide_for_review", f"tone-risk term: {term!r}",
                              "Say it kindly or not at all — how about starting with something you like?")
    return GateResult("allow", "clean")


@dataclass
class Comment:
    comment_id: str
    from_handle: str
    to_handle: str
    text: str
    created_day: str
    gate: GateResult = field(init=False)

    def __post_init__(self) -> None:
        self.gate = comment_gate(self.text)

    @property
    def visible(self) -> bool:
        return self.gate.action == "allow"


# rate limits — friction is a feature: no infinite engagement loops
RATE_LIMITS = {"comments_per_day": 20, "stories_per_day": 3,
               "feed_refresh_suggestions_per_day": 10,
               "notifications_per_day": 5}


# ============================================================================
# 7. MOOD BOARDS — a named collection of looks + palette + intent
# ============================================================================

@dataclass
class MoodBoard:
    board_id: str
    name: str                      # e.g. "Rainy Manila commute"
    look_codes: List[str]          # sd2 codes — boards share as links too
    palette: List[str]             # color names from the app vocab
    intent: str = ""               # one-line feeling, optional

    def __post_init__(self) -> None:
        if not 1 <= len(self.look_codes) <= 12:
            raise ValueError("a mood board holds 1–12 looks")
        for c in self.look_codes:
            decode_share_code(c)   # fail fast

    def to_share_code(self) -> str:
        return encode_share_code([("TOP", "BOARD", 0)], title=f"board:{self.name}",
                                 note=self.intent[:140])


# ============================================================================
# 8. STYLE DNA — personality-of-style score powered by the real recommender
# ============================================================================

def _import_recommender():
    here = os.path.dirname(os.path.abspath(__file__))
    path = os.path.join(here, "recommender.py")
    spec = importlib.util.spec_from_file_location("styledrop_recommender", path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = mod          # required: dataclasses inspect sys.modules
    spec.loader.exec_module(mod)
    return mod


def style_dna(wardrobe, logs, *, occasion: str = "Casual", weather: str = "mild",
              style: str = "Any", mood: str = "Calm") -> Dict[str, Any]:
    """Compute a Style DNA score 0–100 from the user's REAL wardrobe + logs.

    Signals (all from recommender.py runs, nothing invented):
      completeness — do top outfits cover Tops/Bottoms/Shoes?
      harmony      — color harmony of the #1 outfit (recommender's own table)
      diversity    — unique pieces surfaced across the top-k outfits
      coverage     — categories of the wardrobe the stylist can actually use
      wear_balance — share of the wardrobe that has been worn at least once
    """
    rec = _import_recommender()
    ctx = rec.Context(occasion=occasion, weather=weather, style=style,
                      color_pref="Any", shoe_pref="Any", mood=mood,
                      wardrobe=list(wardrobe))
    k = 5
    recs = rec.recommend(ctx, list(logs), k=k)
    n = len(wardrobe)
    if not n or not recs:
        return {"score": 0, "band": "Building", "archetype": "Emerging",
                "palette": [], "n_items": n, "n_logs": len(logs),
                "components": {}}

    completeness = sum(1 - len(r["missing"]) / 3 for r in recs) / len(recs)
    top_items = recs[0]["items"]
    if len(top_items) >= 2:
        pairs = [(a, b) for i, a in enumerate(top_items)
                 for b in top_items[i + 1:]]
        harmony = sum(rec.color_harmony(a.color, b.color) for a, b in pairs) / len(pairs)
    else:
        harmony = 0.5
    filled = sum(len(r["items"]) for r in recs)
    diversity = len({i.id for r in recs for i in r["items"]}) / max(1, filled)
    categories_in_recs = {i.category for r in recs for i in r["items"]}
    coverage = len(categories_in_recs) / max(1, len({i.category for i in wardrobe}))
    wear_balance = sum(1 for i in wardrobe if i.timesWorn > 0) / n

    score_0_1 = (0.30 * completeness + 0.25 * harmony + 0.20 * diversity
                 + 0.15 * coverage + 0.10 * wear_balance)
    score = round(100 * score_0_1)

    archetype_counts: Dict[str, int] = {}
    for r in recs:
        for it in r["items"]:
            archetype_counts[it.style] = archetype_counts.get(it.style, 0) + 1
    archetype = (max(archetype_counts, key=archetype_counts.get)
                 if archetype_counts else "Emerging")
    palette_counts: Dict[str, int] = {}
    for r in recs:
        for it in r["items"]:
            palette_counts[it.color] = palette_counts.get(it.color, 0) + 1
    palette = [c for c, _ in sorted(palette_counts.items(),
                                    key=lambda kv: -kv[1])[:3]]

    if score >= 85:
        band = "Signature"
    elif score >= 70:
        band = "Refined"
    elif score >= 50:
        band = "Emerging"
    else:
        band = "Building"
    return {"score": score, "band": band, "archetype": archetype,
            "palette": palette, "n_items": n, "n_logs": len(logs),
            "components": {"completeness": round(completeness, 3),
                           "harmony": round(harmony, 3),
                           "diversity": round(diversity, 3),
                           "coverage": round(coverage, 3),
                           "wear_balance": round(wear_balance, 3)}}


# ============================================================================
# 9. SOCIAL-PROOF IN ONBOARDING — honest, aggregate, non-comparative
# ============================================================================

def onboarding_social_proof(total_members: int,
                            looks_shared_today: int) -> List[str]:
    """Lines shown on the welcome screen. Rules: aggregate numbers only,
    no names, no activity feeds of others, no 'X friends joined' pressure —
    the fastest way to make a wardrobe app toxic is to make it a popularity
    contest on day one."""
    if total_members <= 0 or looks_shared_today < 0:
        return ["Your atelier is waiting. Hang your first piece."]
    return [f"{total_members:,} people hang their wardrobe here.",
            f"{looks_shared_today:,} looks were pinned today.",
            "No follower counts. No streaks. Just your clothes."]


# ============================================================================
# 10. DEMO / ROUND-TRIP TEST — `python3 sharing.py`
# ============================================================================

def _demo() -> None:
    rec = _import_recommender()
    wardrobe = rec.mock_wardrobe()
    logs = rec.mock_logs()
    first = {cat: next(i for i in wardrobe if i.category == cat)
             for cat in ("Tops", "Bottoms", "Shoes", "Outerwear")}

    print("=" * 78)
    print("SHARING.PY — ROUND-TRIP DEMO (real run, real repo schema)")
    print("=" * 78)

    # --- TEST 1: sd2 encode → URL → decode → deep equality -------------------
    look = SharedLook(
        items=[("TOP", first["Tops"].id, 20), ("BOTTOM", first["Bottoms"].id, 10),
               ("SHOES", first["Shoes"].id, 15), ("OUTER", first["Outerwear"].id, 30)],
        title="Rainy commute", occasion="Work", weather="cold",
        note="Quiet layers for a wet Tuesday.", handle="linen-rail-42")
    code = look.to_share_code()
    url = build_share_url(code)
    back = decode_share_code(code)
    assert back["items"] == look.items, "round-trip items mismatch"
    assert back["title"] == look.title and back["note"] == look.note
    print(f"[TEST 1] sd2 encode/decode round-trip .......... PASS")
    print(f"         items : {back['items']}")
    print(f"         title : {back['title']!r}  occasion: {back['occasion']!r}")
    print(f"         URL   : {url}")

    # --- TEST 2: legacy sd1 (outfit_builder.html format) ----------------------
    import base64 as _b64
    legacy_payload = [["TOP", "T1", 20], ["BOTTOM", "B1", 10], ["SHOES", "S1", 15]]
    legacy_code = _b64.b64encode(
        json.dumps(legacy_payload).encode()).decode()   # exactly what btoa() does
    legacy = decode_share_code(legacy_code)
    assert legacy["version"] == 1 and legacy["items"] == [
        ("TOP", "T1", 20), ("BOTTOM", "B1", 10), ("SHOES", "S1", 15)]
    print("[TEST 2] legacy sd1 (builder #look=) decode .... PASS")

    # --- TEST 3: garbage input ------------------------------------------------
    try:
        decode_share_code("!!!!not-a-code!!!!")
        raise SystemExit("TEST 3 FAILED: garbage accepted")
    except ShareDecodeError as e:
        print(f"[TEST 3] garbage rejected ..................... PASS  ({e})")

    # --- TEST 4: tampered payload (flip one char) -----------------------------
    tampered = code[:-1] + ("A" if code[-1] != "A" else "B")
    try:
        decode_share_code(tampered)
        raise SystemExit("TEST 4 FAILED: tampered payload accepted")
    except ShareDecodeError as e:
        print(f"[TEST 4] tampered payload rejected ............ PASS  ({e})")

    # --- TEST 5: zip-bomb / oversize guard ------------------------------------
    bomb = encode_share_code([("TOP", "T1", 20)], note="x")
    body = bomb[len(WIRE_PREFIX):].split(CHECKSUM_SEP)[0]
    huge = json.dumps({"v": 2, "i": [["TOP", "T" * 40, 20]] * 8,
                       "n": "x" * 20000}, separators=(",", ":")).encode()
    bomb_body = _b64url_encode(zlib.compress(huge, 9))
    try:
        decode_share_code(f"{WIRE_PREFIX}{bomb_body}{CHECKSUM_SEP}{_checksum(bomb_body)}")
        raise SystemExit("TEST 5 FAILED: oversized payload accepted")
    except ShareDecodeError as e:
        print(f"[TEST 5] oversized payload rejected ........... PASS  ({e})")

    # --- TEST 6: Style DNA via the real recommender ---------------------------
    dna = style_dna(wardrobe, logs)
    print(f"[TEST 6] Style DNA ............................ score={dna['score']}")
    print(f"         band={dna['band']}  archetype={dna['archetype']}"
          f"  palette={dna['palette']}")
    print(f"         components={dna['components']}")

    # --- TEST 7: anonymization ------------------------------------------------
    handle = anonymize_handle("user-7f3a9c@example.com", salt="demo-salt")
    feed = FeedItem("f-001", handle,
                    looks=[code], tags=FeedItem.validate_tags(
                        ["Old Money", "Work", "Winter", "definitely-not-in-vocab"]),
                    resonance="warm", created_day="2026-09-17")
    assert "user-7f3a9c" not in feed.to_dict()["handle"]
    print(f"[TEST 7] anonymized handle .................... {handle}"
          f"  (tags kept: {feed.tags})")

    # --- TEST 8: comment gate -------------------------------------------------
    for text in ("The color mix feels so calm!", "no offense but this is mid",
                 "this is ugly trash"):
        g = comment_gate(text)
        print(f"[TEST 8] gate({text[:28]!r:32}) -> {g.action:15} nudge={g.nudge!r}")

    # --- TEST 9: challenges + stories + mood board + onboarding ---------------
    ch = default_challenges()[0]
    ok, why = ch.entry_is_valid(look, {i.style for i in wardrobe})
    assert ok, why
    story = StyleStory(handle, [StoryFrame(code, caption="Wet Tuesday uniform")])
    board = MoodBoard("mb-1", "Rainy Manila commute", [code], ["Navy", "Beige"])
    proof = onboarding_social_proof(12_400, 318)
    print(f"[TEST 9] challenge '{ch.name}' entry ........... PASS ({why})")
    print(f"         story frames={len(story.frames)} "
          f"view-band={story_view_band(13)}  board='{board.name}'")
    print(f"         onboarding proof: {proof}")


if __name__ == "__main__":
    _demo()
