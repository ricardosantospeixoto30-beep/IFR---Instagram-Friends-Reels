# Implementation Plan – Friends Reels Inbox (Personal Android App)

## Overview
This document captures the full technical approach, architecture, and step‑by‑step plan to build an Android application that imports Instagram Reels received in DMs, displays them in a vertical swipe‑able feed, and lets the user react or reply directly to the original Instagram conversation.

---

## 1️⃣ Technical Feasibility Matrix

| Approach | Capabilities | Limitations | Permissions / UX impact | Stability / Future‑proof | Effort |
|----------|--------------|-------------|--------------------------|---------------------------|--------|
| **A – Instagram Graph / Messenger API** | Can read/write DMs **only** for Business/Creator accounts. | Personal accounts cannot read personal DMs. Requires a Facebook‑Developer app and approval. | OAuth flow, secret storage. | Very high risk – likely *Not Available* for a personal project. | ❌ (discard) |
| **B – Deep‑links / Intent URIs** (`instagram://reel/<code>`, `instagram://direct`) | Opens Instagram directly to a specific Reel or DM thread. | Cannot retrieve a list of Reels or send reactions programmatically. | No special permission – just an `Intent.ACTION_VIEW`. | Stable (official Instagram deep links). | ✅ (tiny) |
| **C – Android Share / Send‑to** | Captures a Reel URL if the user manually shares it. | Requires manual sharing for each Reel – not scalable. | No extra permission, but user‑heavy. | Works today, but unsuitable for bulk import. | ❌ (skip) |
| **D – AccessibilityService + UI Automation** | • Reads the Instagram UI to discover conversations and Reel messages.<br>• Extracts Reel URLs.<br>• Performs taps to send reactions or type replies **inside the original DM**. | Must run while Instagram is in the foreground; UI changes in Instagram may break it. | Requires `android.permission.BIND_ACCESSIBILITY_SERVICE` – user must enable the service in Settings. | Moderately stable; UI breakage requires updates. | 🟠 Medium (implementation‑heavy but viable) |
| **E – Notification Action + PendingIntent** | Could add a custom action to Instagram push notifications. | Instagram notifications do not expose Reel URLs, so still need UI automation. | Needs NotificationListenerService permission. | Same fragility as D. | 🟠 Medium |
| **F – Android MediaStore / Local Cache** | Stores downloaded Reel videos or thumbnails locally. | Does not provide source metadata. | No extra permission beyond scoped storage. | Stable. | ✅ Low |
| **G – WorkManager (periodic sync)** | Schedules a background job that launches the Accessibility flow on user‑triggered sync. | Still depends on UI automation; cannot run when device is locked. | No extra OS permission. | Stable. | ✅ Low |

**Verdict:** The only practical approach for a personal MVP that both imports old Reels **and** sends reactions/replies is **Approach D (AccessibilityService)**, complemented by deep‑links for fallback and Room for local storage.

---

## 2️⃣ End‑to‑End Architecture (high level)
```
+-------------------+        +-------------------+        +----------------------+
|   Android UI (Jet| <---> |   Repository      | <--->  |   Room (SQLite) DB   |
|   pack Compose)   |        | (Kotlin Coroutines)    |   (Reel, Conv, Meta) |
+-------------------+        +-------------------+        +----------------------+
        ^                           ^                                 ^
        |                           |                                 |
        |   +-------------------+   |   +--------------------------+   |
        +---|   Accessibility   |---+---|   Instagram UI Wrapper   |
            |   Service (UI)   |       |   (find reels, click,   |
            +-------------------+       |    send reaction, etc.) |
                                         +--------------------------+

+-------------------+   (optional)   +-------------------+
|   Deep‑Link Intent| <-------------- |   Instagram App   |
+-------------------+                +-------------------+
```
* The **AccessibilityService** is the only component that interacts with Instagram’s UI.
* The **UI layer** reads from Room and shows a vertical feed using Jetpack Compose.
* **Deep‑link intents** provide the “Open in Instagram” fallback.

---

## 3️⃣ Implementation Plan (from 0)

### Phase 0 – Project Bootstrap & Tooling
1. Create a new Android Studio project (Kotlin, min SDK API 21, target latest).  
2. Add required libraries: Jetpack Compose, Room, WorkManager, OkHttp, Coroutines.  
3. Scaffold folders: `ui/`, `data/`, `service/`, `util/`.  
4. Commit the scaffold to a local Git repo.

### Phase 1 – Proof‑of‑Concept Accessibility Service
1. Implement `InstagramAutomationService` subclass of `AccessibilityService`.  
2. Provide a simple Settings activity that explains why the service is needed.  
3. Write a demo command that:
   * Launches Instagram via an explicit intent.  
   * Waits for the **Direct** tab.  
   * Opens the first conversation.  
   * Scans the message list for nodes that contain a Reel thumbnail.  
   * Extracts the Reel deep‑link URL (using node text or clipboard fallback).  
   * Sends the URL back to the app via a `LocalBroadcast`.
4. Manually test on a device (Android 16+). Success = one Reel URL logged.

### Phase 2 – Data Model & Local Persistence
* Define Room entities:
  * `Conversation` (id, name, isGroup, avatarUrl).  
  * `Reel` (id, conversationId, senderId, senderName, reelUrl, thumbnailUrl, sentAt, importedAt, viewedAt?, repliedAt?, reactionSent?, replyText?, status).  
  * `ReelStatus` enum (UNSEEN, SEEN, REPLIED, REACTION_SENT, FAILED).
* DAO with bulk insert (`IGNORE` on conflict) and query functions (filters by conversation, status, etc.).
* Repository exposing `Flow<List<Reel>>` for UI consumption.
* Simple JSON export/import utility for backups.

### Phase 3 – UI – Reel Feed & Navigation
1. Bottom navigation with three tabs: **Reels**, **Conversations**, **Settings**.
2. **Reels screen**: full‑screen vertical pager (Compose) showing video (ExoPlayer), sender avatar/name, caption, reaction bar (❤️, 😂), reply field + Send button.
3. **Conversation list**: shows each conversation with count of unseen reels, checkbox for selection.  Buttons for **Import All** / **Import Selected**.
4. **Settings**: toggle AccessibilityService, clear data, export backup, filter toggles.
5. Swipe‑up / swipe‑down gestures move to next/previous Reel.

### Phase 4 – Synchronisation & Import Logic
1. `SyncManager` orchestrates the import:
   * Launch Instagram.
   * For each selected conversation:
     * Open conversation via service.
     * Scroll through message list, collect Reel nodes.
     * Convert each node to a `Reel` entity (extract URL, sender, timestamp).
     * Insert into Room (duplicates ignored).
   * Return to our app after each conversation.
2. Scrolling uses `AccessibilityNodeInfo.ACTION_SCROLL_FORWARD` until the action fails.
3. Progress shown via modal indicator with a simple text counter.
4. Manual sync only (user taps a button).

### Phase 5 – Reaction & Reply Automation
* Inside `InstagramAutomationService` implement two public commands:
  * `sendReaction(reelId, reaction)` – locate the Reel in the conversation, tap the emoji button, pick the requested emoji, verify UI change, report success.
  * `sendReply(reelId, text)` – locate Reel, tap the reply field, set text via `ACTION_SET_TEXT`, tap Send, verify message appears.
* Service communicates results back to the UI (success / FAILED) via `LocalBroadcast`.
* UI updates Room status accordingly.

### Phase 6 – Deep‑Link Fallback
Add a button on each Reel page that launches an intent:
```kotlin
val intent = Intent(Intent.ACTION_VIEW, Uri.parse(reelUrl)).apply {
    setPackage("com.instagram.android")
}
startActivity(intent)
```

### Phase 7 – Settings, Filters & Polish
* Filter chips on Reels screen (All, Unseen, Not responded, By person).  
* Persist user preferences (selected conversations, filter choices) with Jetpack `DataStore`.  
* Provide a clear UI for enabling the AccessibilityService (direct link to Settings.ACTION_ACCESSIBILITY_SETTINGS).

### Phase 8 – Testing & QA
| Test | Goal |
|------|------|
| Import correctness | Run sync on a test account with many Reel DMs; verify each Reel appears once with proper metadata. |
| Reaction round‑trip | React to a Reel; confirm the reaction appears in Instagram. |
| Reply round‑trip | Send a reply; confirm it shows in Instagram and UI marks REPLIED. |
| Compatibility on Android 16 | Ensure the app launches and the service works on an API 16 device (fallback to minimal XML layout if needed). |
| Permission flow | Verify the AccessibilitySettings dialog appears and that disabling the service stops sync/reaction features. |
| Duplicate handling | Run sync twice; DB size should not increase after the second run. |

All tests are manual (personal project) but results are logged in `memory/` for future reference.

### Phase 9 – Security / Privacy Review
* **No password storage** – the app never asks for Instagram credentials; it only interacts with the UI of the Instagram app that the user is already logged into.
* **Clear permission notice** – first‑launch dialog explains the AccessibilityService usage and that the app will read the Instagram UI. Disabling the service disables all import/reaction features.
* **Scoped storage** – backups are written to the app‑specific external files directory; no broad storage permission is required.

---

## 4️⃣ What I Need From You
1. **A test Instagram account** (personal or newly created) that already has a number of Reel DMs. This will be used for the PoC and subsequent sync testing.
2. **Device access** (Android 16+) where you can install the debug APK and enable the AccessibilityService.

No other resources are required at this stage. Once the PoC validates that we can extract Reel URLs via the AccessibilityService, I can proceed to flesh out the full codebase (Room entities, Compose UI, sync manager, reaction/reply commands).

---

*Let me know when the test account is ready and you have a device available, and I’ll start coding the first components.*
