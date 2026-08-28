package com.example.friendsreels.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Placeholder Accessibility Service.
 *
 * The real Reel-discovery / reaction / reply logic will be added incrementally
 * during Phase 1 (Proof of Concept). For now this only registers the service
 * so the user can enable it in Android settings and confirm the wiring works.
 */
class InstagramReaderService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "InstagramReaderService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op for the initial skeleton commit.
    }

    override fun onInterrupt() {
        Log.w(TAG, "InstagramReaderService interrupted")
    }

    companion object {
        private const val TAG = "IGReaderService"
    }
}
