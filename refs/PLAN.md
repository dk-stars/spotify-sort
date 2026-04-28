
## Plan: Spotify Smart Librarian MVP

**TL;DR**: Refactor the existing single-user OAuth into a proper multi-user session system backed by PostgreSQL, build the tagging + Sync & Suggest engine as pure Java services, expose a REST API, then build a React/Redux/TS/SCSS dashboard on top.

---

### Current State (what exists)
| File | Status |
|---|---|
| `SpotifyConfig` | Keep, but extend with per-user instance factory |
| `SpotifyAuthController` | **Refactor** — stores token on singleton bean, broken for multi-user |
| `SpotifyPlaylistService` | **Rename/expand** into full Spotify data layer |
| `model/`, `repository/`, `engine/` | Empty — all to be built |

---

### Phase 1 — Infrastructure & Auth Overhaul

0. Setup docker-compose for DB
1. Add `spring-boot-starter-data-jpa` + PostgreSQL driver to pom.xml
2. Configure application.yml: datasource, JPA DDL, CORS allowed-origins for React dev server
3. Create `User` entity — `spotifyId`, `displayName`, `accessToken`, `refreshToken`, `tokenExpiresAt`
4. Create `UserRepository`
5. Create `TokenService` — factory method that produces a per-user `SpotifyApi` instance hydrated with that user's tokens; auto-refreshes when expired
6. **Refactor** `SpotifyAuthController.callback()` — upsert `User` in DB, store `userId` in HTTP session
7. Add `SecurityConfig` — CORS for `localhost:3000`, permit `/api/auth/**`, session-based identity
*All steps sequential; establishes the foundation everything else depends on.*

---

### Phase 2 — Spotify Data Layer
*(depends on Phase 1)*

8. Create `SpotifyClientService` (replaces `SpotifyPlaylistService`):
   - `getUserPlaylists(userId)` — paginated, returns all playlists
   - `getPlaylistTracks(userId, playlistId)` — paginated
   - `getAudioFeatures(userId, List<trackId>)` — batched, max 100 per call
   - `getArtists(userId, List<artistId>)` — batched, max 50 per call
9. Create `ScanJob` entity — `userId`, `sourcePlaylistId`, `status` enum (PENDING/RUNNING/DONE/FAILED), `resultJson` blob
10. Create `ScanJobRepository`

---

### Phase 3 — Tagging Engine
*(depends on Phase 2; steps 11–13 can run in parallel)*

11. `GenreTagger` — takes `List<Artist>` → extracts `genres[]` → normalizes (lowercase, trim, deduplicate)
12. `MoodTagger` — deterministic rules on `AudioFeatures`:
    - `acousticness > 0.7` → `"calm"`
    - `instrumentalness > 0.5` → `"no-vocal"`
    - `energy > 0.8` AND `tempo > 140` → `"high-bpm"`
    - `danceability > 0.7` → `"danceable"`
    - `valence > 0.7` → `"happy"` / `valence < 0.3` → `"dark"`
13. `TrackTagger` — orchestrates 11 + 12, produces `TaggedTrack(trackId, name, tags: Set<String>)`

---

### Phase 4 — Sync & Suggest Engine
*(depends on Phase 3)*

14. `SyncSuggestEngine.analyze(taggedTracks, existingPlaylists, threshold)`:
    - Groups tracks by tag, then for each tag:
    - **Condition A** — tag name matches existing playlist → `playlistsToUpdate`
    - **Condition B** — no match AND `count >= threshold` → `newIdeas`
    - **Condition C** — no match AND `count < threshold` → silently ignored
    - Returns `SyncSuggestResult` DTO (serializable to JSON for `ScanJob.resultJson`)

---

### Phase 5 — Scan Orchestration & REST API
*(depends on Phase 4)*

15. `ScanService` (`@Async`) — `createScan()` saves job PENDING and returns `jobId`; `runScan()` drives the full pipeline (fetch → tag → engine → persist result)
16. `ScanController`:
    - `POST /api/scan` → starts scan, returns `{ jobId }`
    - `GET /api/scan/{jobId}` → returns status + result when DONE (frontend polls this)
17. `PlaylistController`: `GET /api/playlists` — source playlist picker data

---

### Phase 6 — Execute Phase
*(depends on Phase 5)*

18. `PlaylistExecutionService`:
    - `executeUpdates(userId, updateActions)` — batch-adds tracks to existing playlists (max 100/request)
    - `executeCreations(userId, createActions)` — creates playlist then batch-adds
19. `ProposalController`: `POST /api/proposal/execute` — accepts user-approved actions JSON, runs execution, returns summary

---

### Phase 7 — React Frontend
*(parallel with Phases 5–6; blocks on API contract from Phase 5)*

20. Bootstrap `frontend/` with **Vite + React + TypeScript + SCSS + Redux Toolkit**
21. Redux slices: `auth`, `playlists`, `scan`, `proposal`
22. Page flow: `LoginPage` → `SourceSelectPage` (playlist picker + threshold slider) → `ScanProgressPage` (polling) → `DashboardPage`
23. `DashboardPage`: two-panel layout
    - **"Playlists to Update"** — checkboxes per playlist
    - **"New Ideas"** — checkboxes per tag with track count
    - "Apply" button → `POST /api/proposal/execute`

---

### Verification
1. Unit tests: `MoodTagger` (assert each rule boundary), `SyncSuggestEngine` (assert all three conditions)
2. `mvn test` passes clean
3. Manual E2E: login → pick source playlist → scan a playlist with 50+ tracks → confirm dashboard splits into two panels correctly → click Apply → verify in Spotify app that tracks were moved

---

### Decisions & Scope
- **Included**: full multi-user auth, PostgreSQL persistence, async scan with polling, React dashboard, execute phase
- **Excluded from MVP**: threshold customization per-tag, undo/rollback, scan history UI, track deduplication across playlists
- **Audio Features**: used in dev mode; production quota extension deferred post-MVP
- **Token refresh**: handled in `TokenService` using `SpotifyApi.authorizationCodeRefresh()` before each API call if `tokenExpiresAt` is past

---

### Further Considerations
1. **Scan result storage**: Storing full `SyncSuggestResult` as a JSON blob in `ScanJob` is simplest for MVP. If you want query-able scan history later, model it as separate `TaggedTrack` + `ProposalItem` tables.
2. **Playlist name matching**: The Sync & Suggest tag-to-playlist match is currently case-insensitive exact match. Fuzzy matching (e.g. `"lo-fi"` ↔ `"lofi"`) would significantly improve UX — worth flagging as a quick win after MVP baseline works.