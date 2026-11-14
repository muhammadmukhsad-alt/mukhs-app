# MukhsApp roadmap (offline, no Firebase / Supabase / internet)

Everything lives on the phone (SQLite + app files) and is built by GitHub Actions.
Phones talk to each other with Google Nearby Connections (Bluetooth + Wi-Fi Direct).

## Honest limits (read first)
- Bluetooth / Wi-Fi Direct reach ~10-100 m. Messages travel farther only by **hopping through other people's phones** (store-carry-forward). Delay = how long until a carrier meets the receiver.
- **Live** calls work only while every hop is connected. Across a town this needs enough phones in a chain, and voice quality drops per hop. True long-range radio (neighbourhood to neighbourhood) needs extra hardware (e.g. LoRa/HF radio bridge); a phone alone cannot do it.
- Automatic porn detection is on-device AI: it will miss some and flag some wrongly. A network-wide "instant permanent ban" needs a signed, reviewable process, otherwise anyone can get anyone banned.

## Phase 1 - Store-and-forward chat (this branch)
- [x] Sealed envelopes (ECDH P-256 + AES-GCM, signed) so carriers cannot read messages
- [x] Carry up to 300 envelopes, 7-day expiry, 8 hops; swap on every connection
- [x] Delivery tombstones so carriers drop delivered messages; sender gets the double tick
- [x] Text via relay (needs the receiver's public key, learned at the first direct meeting)
- [ ] Voice notes via relay (chunked envelopes)
- [ ] Connect to non-contacts as carriers (today only phone contacts are discovered)
- [ ] Username addressing (directory gossiped with public keys)
- [ ] "last seen" shown from relay activity; badges already exist

## Phase 2 - Reference-video UI gaps
Done already (v0.17): splash, light/dark doodle, filter chips, bottom tabs, FAB, call cards "Tap to call back", call screens, status.
To do: Calls tab quick actions (Call / Schedule / Keypad / Favourites), real groups and broadcast lists (data model + create flow), group info (Media / Links / Docs, chat lock, disappearing), media editor polish.

## Phase 3 - Feed and Reels mode
Posts/short videos stored locally, synced when phones meet (same relay with public envelopes), chat/feed toggle.

## Phase 4 - Moderation
On-device NSFW classifier before upload and before showing; signed reports; local blacklist gossiped across the mesh.

## Phase 5 - Multi-hop live calls
Relay audio frames across connected neighbours (mesh), jitter buffer, optional external radio bridge.
