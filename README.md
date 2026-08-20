# Friends Reels Inbox (Android PoC)

A personal Android application that reads Instagram Direct messages, extracts Reel URLs, and displays them in a swipe‑able feed. The app uses an **AccessibilityService** to automate UI interactions with the Instagram app (no official API for personal DM access).

## What the repo contains
- **Full Gradle project** (`settings.gradle`, `app/build.gradle`).
- **AndroidManifest.xml** with the AccessibilityService declaration.
- **Service configuration** (`res/xml/instagram_automation_service_config.xml`).
- **String resources** and **theme** definitions.
- **MainActivity** (Compose UI) with a sync button and a shortcut to enable the service.
- **InstagramAutomationService** – a proof‑of‑concept service that:
  1. Launches Instagram.
  2. Opens the Direct tab.
  3. Opens the first conversation.
  4. Finds a Reel thumbnail, long‑presses it, copies the deep‑link URL, and logs it.
- **CONVERSATION.md** – a transcript of the design discussion that produced this code.
- **README.md** (this file).
- **.gitignore** (standard Android ignore patterns).

## How to get it running locally
1. **Clone or copy the folder** to a location on your machine.
2. Open the folder in **Android Studio** (File → Open…).
3. Let Gradle sync and download the required dependencies.
4. Connect an Android device (or start an AVD) with **USB debugging** enabled.
5. Build & run the app on the device.
6. In the app, tap **"Enable Accessibility Service"** – this opens the system settings.
7. Find **Friends Reels Inbox** under *Accessibility services* and toggle it **ON**.
8. Return to the app and press **"Sync selected conversations"**.
9. Open **Logcat** and filter by tag `IGAutomationService`. You should see a line similar to:
   ```
   I/IGAutomationService: Extracted Reel URL: https://www.instagram.com/reel/... 
   ```
   If you see a URL, the core feasibility is proven.

## Next steps (once PoC is green)
- Bulk‑import all conversations (scroll + pagination). 
- Persist Reels in **Room** database.
- Build the vertical feed UI with video playback, reactions (❤️, 😂) and reply field.
- Add conversation selection UI, filters, and settings.
- Harden error handling for UI changes and add a fallback "Open in Instagram" button.
- (Optional) Prepare for Play Store publication.

## License & Disclaimer
This project is for **personal use only**. It leverages Android accessibility features to interact with the Instagram app, which may break if Instagram changes its UI. Use responsibly and only on devices you control.
