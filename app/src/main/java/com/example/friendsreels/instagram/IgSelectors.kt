package com.example.friendsreels.instagram

/**
 * Centralized selectors for interacting with the Instagram app via
 * AccessibilityService.
 *
 * The vast majority of Instagram screens still expose native views with stable
 * `resource-id`s (e.g. `com.instagram.android:id/direct_tab`). Those IDs are
 * language-independent and are the preferred way to identify UI elements.
 *
 * Whenever an element only surfaces through `contentDescription` or `text` we
 * store both the Portuguese (Portugal) and English variants observed during
 * PoC-2 mapping. Add more variants as we run into them.
 *
 * All resource-id constants exclude the package prefix; use [id] to build the
 * full name expected by `AccessibilityNodeInfo.findAccessibilityNodeInfosByViewId`.
 */
object IgSelectors {

    const val IG_PACKAGE = "com.instagram.android"

    /** Build the full resource id used by the AccessibilityService APIs. */
    fun id(shortName: String): String = "$IG_PACKAGE:id/$shortName"

    // ---------------------------------------------------------------------
    // Bottom navigation (Home screen)
    // ---------------------------------------------------------------------
    object BottomNav {
        const val FEED_TAB = "feed_tab"       // Início / Home
        const val CLIPS_TAB = "clips_tab"     // Reels
        const val DIRECT_TAB = "direct_tab"   // Mensagem / Direct
        const val SEARCH_TAB = "search_tab"
        const val PROFILE_TAB = "profile_tab"
    }

    // ---------------------------------------------------------------------
    // Direct / Inbox screen
    // ---------------------------------------------------------------------
    object Inbox {
        // The Direct inbox is now implemented in Jetpack Compose so most nodes
        // do not expose a resource-id. Rows are identifiable by their
        // contentDescription following the pattern:
        //   "<name>, [não lidos, ]<preview> ·, <time>"
        // We rely on that pattern to enumerate conversations.

        /** Text of the "Messages" section title on the inbox. */
        val TITLE_MESSAGES = setOf("Mensagens", "Messages")

        /** Text of the "Requests" entry point. */
        val TITLE_REQUESTS = setOf("Pedidos", "Requests")
    }

    // ---------------------------------------------------------------------
    // Thread / conversation screen
    // ---------------------------------------------------------------------
    object Thread {
        const val CONTAINER_PARENT = "layout_container_parent"
        const val FRAGMENT_CONTAINER = "thread_fragment_container"

        // Header
        const val HEADER_BACK = "header_left_button"
        const val HEADER_TITLE_SUBTITLE_CONTAINER = "header_title_subtitle_container"
        const val HEADER_TITLE = "header_title"       // Person name or group name
        const val HEADER_SUBTITLE = "header_subtitle" // @username (empty for groups)

        // Message list
        const val MESSAGE_LIST = "message_list"

        // A message's outer container. Flags include `L` (long-clickable) so we
        // can call performAction(ACTION_LONG_CLICK) directly on it to open the
        // Instagram context menu without triggering system gestures such as
        // OxygenOS's "Portal de conteúdo".
        const val MESSAGE_CONTENT = "message_content"

        // Container types inside `message_content` that identify shared media.
        //   PORTRAIT: large inline media (usually Reels/portrait video shares).
        //   GENERIC: smaller card preview (also used for some Reel shares).
        const val MESSAGE_MEDIA_PORTRAIT = "message_content_portrait_xma_container"
        const val MESSAGE_MEDIA_GENERIC = "message_content_generic_xma_container"

        // Inside the media container we can find the ORIGINAL AUTHOR of the reel
        // (this is the account that posted the reel, NOT the friend who shared
        // it in DM). Both containers expose these.
        const val REEL_AUTHOR_AVATAR = "profile_attribution_picture"
        const val REEL_AUTHOR_USERNAME = "title_text"

        // The DM sender is identified by the presence of `sender_avatar` inside
        // the `message_content`. Messages from ourselves do NOT include it.
        //
        // GROUP NOTE (validated 2025-08-28 with dump `ignore sent and group.txt`
        // WINDOW[3] APPLICATION): the `sender_avatar` node has a generic
        // `contentDescription="Foto de perfil" / "Profile picture"` and there
        // is NO TextView inside `message_content` carrying the group member's
        // name/username. The context menu opened via long-press also only
        // exposes date/time (`context_menu_item_sub_label="29/07, 8:05 DA
        // TARDE"`). Consequence: on the current IG build we cannot identify
        // which group member shared a given Reel purely from the DM screen.
        // Options for a future iteration: (a) tap the `sender_avatar` to open
        // the member's profile and read the username, or (b) match the avatar
        // image against the group members list. For the MVP the feed shows
        // "from <group name>" instead of the individual member.
        const val SENDER_AVATAR = "sender_avatar"

        // Reactions pill shown attached to the message (empty when no reaction).
        const val REACTIONS_PILL_CONTAINER = "message_reactions_pill_container"
        const val REACTION_ADD_BUTTON = "reaction_add"

        // Forward shortcut visible while the message is highlighted.
        const val FORWARD_SHORTCUT = "forwarding_shortcut_button"

        // ---------------------------------------------------------------
        // Reply-context marker (s50).
        //
        // When a friend REPLIES to a Reel we sent, IG renders the whole
        // exchange as a SINGLE `message_content` bubble containing:
        //   - Button id=direct_context_reply_context_info_text_view
        //     desc="Respondeu-te" (localized)
        //   - FrameLayout id=message_content_portrait_xma_container
        //     (the Reel we sent that they replied TO)
        //   - TextView id=direct_text_message_text_view (their reply text)
        //
        // Pre-s50 `enumerateReels` treated the embedded portrait as a
        // shared Reel to enrich — but tapping it opens their reply
        // thread instead of the Reel viewer, breaking the copy-link
        // chain. Reported by the user via `docs/screen-dumps/dump.txt`.
        // s50 detects this marker inside a bubble and SKIPS the bubble
        // entirely from enumeration.
        // ---------------------------------------------------------------
        const val REPLY_CONTEXT_INFO_TEXT = "direct_context_reply_context_info_text_view"

        // Composer (text field to send messages back into the DM)
        const val COMPOSER_BAR = "message_composer_bar"
        const val COMPOSER_EDITTEXT = "row_thread_composer_edittext"

        // Once the composer has text, IG replaces the voice/gallery/sticker
        // strip with a single "Send" button. The id has changed over the
        // years and we haven't captured a dump of the populated composer yet,
        // so we probe a list of known candidates. Fallback is a search by
        // `contentDescription` in the localized labels below.
        val COMPOSER_SEND_BUTTON_CANDIDATES = listOf(
            "row_thread_composer_send_button",
            "row_thread_composer_send",
            "composer_send_button",
            "send_button",
        )

        /** Localized contentDescriptions for the composer send button. */
        val COMPOSER_SEND_LABELS = setOf("Enviar", "Send")

        // When the user picks "Responder" from the context menu, IG shows a
        // reply-preview strip on top of the composer. Useful as a positive
        // signal that the reply flow is truly active.
        const val COMPOSER_REPLY_BAR_CONTAINER = "message_composer_reply_bar_container"

        // ---------------------------------------------------------------
        // Top-of-conversation header (validated 2026-08-31 in
        // `docs/screen-dumps/dump.txt`, s45 device dump). When the user
        // scrolls the `message_list` all the way to the very first
        // message of a DM, IG renders a "header" card at the top with:
        //
        //   FrameLayout id=user_avatar                    (large 330×330 avatar)
        //   TextView   id=other_user_full_name_or_username (display name)
        //   TextView   id=network_attribution              (the @handle)
        //   Button     id=view_profile_button              (text "Ver perfil" / "View profile")
        //
        // The presence of any of these — but especially the
        // `view_profile_button` — is the definitive signal that we've
        // reached the beginning of the conversation and further backward
        // scrolls will not surface new content. s46 uses this to abort
        // both the batch-enrichment locate loop and the history-scroll
        // discovery early, replacing the buggy "stall detection" heuristic
        // that was removed in s44.
        // ---------------------------------------------------------------
        const val HEADER_VIEW_PROFILE_BUTTON = "view_profile_button"
        const val HEADER_USER_AVATAR = "user_avatar"
        const val HEADER_OTHER_USER_FULLNAME = "other_user_full_name_or_username"
        const val HEADER_NETWORK_ATTRIBUTION = "network_attribution"
    }

    // ---------------------------------------------------------------------
    // Context menu (opened after long-press on a message)
    // ---------------------------------------------------------------------
    object ContextMenu {
        // Whole container that wraps the highlighted message + reactions row
        // on the main window.
        const val MESSAGE_ACTIONS_CONTAINER = "message_actions_container"

        // Compose-based menu stub visible on the MAIN window. Its actual
        // children are laid out with 0 height because the real menu is
        // rendered in a SEPARATE popup window (see CONTEXT_MENU_LIST).
        const val COMPOSE_MENU_CONTAINER = "compose_context_menu"

        // Quick-reaction row that shows the 6 preset emojis right above the
        // highlighted message. Lives on the MAIN window.
        const val QUICK_REACTION_ROW = "creation_row_container"

        // Each emoji is an ImageView with resource-id `id/image` and a
        // contentDescription that starts with the actual emoji character
        // followed by the localized word for "Reaction".
        const val QUICK_REACTION_IMAGE = "image"
        const val QUICK_REACTION_ALL_EMOJIS_BUTTON = "customize_icon"

        // Popup window with the full action list (Reply, Add sticker,
        // Forward, Pin, Delete, ...). AccessibilityService.getWindows() must
        // be used to reach it - it is NOT part of rootInActiveWindow.
        const val CONTEXT_MENU_LIST = "context_menu_options_list"
        const val CONTEXT_MENU_ITEM = "context_menu_item"
        const val CONTEXT_MENU_ITEM_LABEL = "context_menu_item_label"
        const val CONTEXT_MENU_ITEM_SUB_LABEL = "context_menu_item_sub_label"

        /** All the emojis exposed on the quick-reaction row. */
        val REACTION_EMOJIS = listOf("❤", "😂", "😮", "😢", "😡", "👍")

        /** Localized suffix used in the contentDescription of each emoji. */
        val REACTION_DESC_SUFFIX = setOf("Reação", "Reaction")

        /**
         * Build the exact contentDescription used by the quick-reaction row
         * for a given emoji (e.g. "❤Reação" in Portuguese).
         */
        fun quickReactionDescriptions(emoji: String): Set<String> =
            REACTION_DESC_SUFFIX.map { emoji + it }.toSet()

        // -----------------------------------------------------------------
        // Localized labels observed on the context_menu_item entries.
        // The list order below matches what shows up in a DM with a shared
        // Reel; more entries may appear for other message types.
        // -----------------------------------------------------------------

        /** Labels for the "Reply" action. */
        val ACTION_REPLY = setOf("Responder", "Reply")

        /** Labels for the "Add sticker" action. */
        val ACTION_ADD_STICKER = setOf("Adicionar sticker", "Add sticker")

        /** Labels for the "Forward" action. */
        val ACTION_FORWARD = setOf("Reencaminhar", "Forward")

        /** Labels for the "Pin" action. */
        val ACTION_PIN = setOf("Afixar", "Pin")

        /** Labels for the "Delete for you" action. */
        val ACTION_DELETE_FOR_YOU = setOf("Eliminar para ti", "Delete for you")

        /** Labels for the "Copy link" action (may not appear for every Reel share). */
        val ACTION_COPY_LINK = setOf("Copiar link", "Copiar ligação", "Copy link")
    }

    // ---------------------------------------------------------------------
    // Reel viewer (native full-screen player opened from a DM bubble)
    //
    // Nodes captured in the session-16 dump (`reel dump.txt` WINDOW[3]).
    // The viewer lives entirely in the main IG APPLICATION window (no
    // separate popup) so rootInActiveWindow is enough after settle.
    // ---------------------------------------------------------------------
    object ReelViewer {
        /** ViewPager that hosts one Reel per page. Presence == viewer open. */
        const val CLIPS_VIEWER_PAGER = "clips_viewer_view_pager"

        /** ViewGroup that wraps the currently playing Reel (has contentDescription "Reel de <autor>"). */
        const val CLIPS_MEDIA_COMPONENT = "clips_media_component"

        /** Original author of the Reel (username shown at the bottom left). */
        const val CLIPS_AUTHOR_USERNAME = "clips_author_username"

        /**
         * Sender of the Reel inside the DM. In 1-a-1 chats this is the
         * interlocutor; in groups this is the specific member who shared the
         * Reel — VERY useful as a fallback for the PoC-4 "sender in a group"
         * open point (see §5 of the progress log).
         */
        const val SENDER_USERNAME_OR_FULLNAME = "sender_username_or_fullname"
        const val SENDER_PROFILE_PIC = "sender_profile_pic"
        const val SENDER_TIMESTAMP = "sender_timestamp"

        /** Right-hand vertical action strip. */
        const val UFI_COMPONENT = "clips_ufi_component"
        const val UFI_LIKE_BUTTON = "like_button"
        const val UFI_COMMENT_BUTTON = "comment_button"
        const val UFI_SAVE_BUTTON = "save_button"

        /** Native Android share sheet entry point (desc="Partilhar" / "Share"). */
        const val UFI_SHARE_BUTTON = "direct_share_button"

        /**
         * RecyclerView at the bottom of the IG share sheet that hosts the
         * external actions row (Add to story, WhatsApp, Share, Copy link,
         * SMS, ...). Every child is an ImageView whose id is a generic
         * `button` — the identification signal is the [COPY_LINK_LABELS]
         * `contentDescription`.
         */
        const val SHARE_SHEET_EXTERNAL_ROW = "direct_external_reshare_row"

        /** ⋮ menu — opens the bottom sheet that (probably) hosts "Copy link". */
        const val UFI_MORE_BUTTON = "clips_ufi_more_button_component"

        /** Reply composer at the bottom of the viewer (alternative reply path). */
        const val REPLY_BAR_EDITTEXT = "reply_bar_edittext"

        /**
         * Localized labels for the "Copy link" entry inside the share sheet.
         * NOTE: session-17 confirmed the ⋮ bottom sheet (`bottom_sheet_container`)
         * does NOT contain "Copy link" — it only has Save / Play / feedback
         * options (Denunciar, Não tenho interesse, ...). "Copy link" lives
         * inside the share sheet opened by [UFI_SHARE_BUTTON] instead.
         */
        val COPY_LINK_LABELS = setOf("Copiar link", "Copiar ligação", "Copy link")

        /**
         * Container of the ⋮ bottom sheet. Present when the sheet is up but
         * NOT a source for copy-link (see COPY_LINK_LABELS note above).
         */
        const val BOTTOM_SHEET_CONTAINER = "bottom_sheet_container"
    }
}
