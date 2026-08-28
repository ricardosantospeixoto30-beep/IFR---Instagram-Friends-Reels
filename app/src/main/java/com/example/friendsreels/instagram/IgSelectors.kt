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
        const val SENDER_AVATAR = "sender_avatar"

        // Reactions pill shown attached to the message (empty when no reaction).
        const val REACTIONS_PILL_CONTAINER = "message_reactions_pill_container"
        const val REACTION_ADD_BUTTON = "reaction_add"

        // Forward shortcut visible while the message is highlighted.
        const val FORWARD_SHORTCUT = "forwarding_shortcut_button"

        // Composer (text field to send messages back into the DM)
        const val COMPOSER_BAR = "message_composer_bar"
        const val COMPOSER_EDITTEXT = "row_thread_composer_edittext"
    }

    // ---------------------------------------------------------------------
    // Context menu (opened after long-press on a message)
    // ---------------------------------------------------------------------
    object ContextMenu {
        // Whole container that wraps the highlighted message + reactions row +
        // the pop-up menu.
        const val MESSAGE_ACTIONS_CONTAINER = "message_actions_container"

        // Compose-based menu with actions (Reply, Copy, Forward, Delete, ...).
        // Its rows do NOT expose resource-ids; they surface as Compose nodes
        // with just `text`. We match by the localized label sets below.
        const val COMPOSE_MENU_CONTAINER = "compose_context_menu"

        // Quick-reaction row that shows the 6 preset emojis right above the
        // highlighted message.
        const val QUICK_REACTION_ROW = "creation_row_container"

        // Each emoji is an ImageView with resource-id `id/image` and a
        // contentDescription that starts with the actual emoji character
        // followed by the localized word for "Reaction".
        const val QUICK_REACTION_IMAGE = "image"
        const val QUICK_REACTION_ALL_EMOJIS_BUTTON = "customize_icon"

        /** All the emojis exposed on the quick-reaction row. */
        val REACTION_EMOJIS = listOf("❤", "😂", "😮", "😢", "😡", "👍")

        /** Localized suffix used in the contentDescription of each emoji. */
        val REACTION_DESC_SUFFIX = setOf("Reação", "Reaction")

        /**
         * Localized labels for the "Reply" action in the pop-up menu.
         * Fill in more variants as we observe them (e.g. brazilian PT).
         */
        val ACTION_REPLY = setOf("Responder", "Reply")

        /** Localized labels for the "Copy link" action. */
        val ACTION_COPY_LINK = setOf("Copiar link", "Copiar ligação", "Copy link")

        /** Localized labels for the "Forward" action. */
        val ACTION_FORWARD = setOf("Reencaminhar", "Forward")

        /** Localized labels for the "Delete" action. */
        val ACTION_DELETE = setOf("Eliminar", "Anular envio", "Unsend", "Delete")
    }
}
