package org.simpmusic.listentogether

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the blocklist gate SimpJam added to [ListenTogetherSession].
 *
 * The session rejects a JOIN_REQUEST from any blocked name *before* the auto-approve path can see
 * it, so a blocked guest is never silently re-admitted. These tests pin the one load-bearing
 * property of that gate — case-insensitive matching against the host's blocklist — without a
 * socket. Constructing the session never connects (that is an explicit later call), so this is a
 * fast, deterministic, offline test unlike [SimpJamRoomE2ETest].
 *
 * The wire-level "blocked joiner gets rejectJoin, not approveJoin" ordering is exercised by the
 * E2E test; here we lock the predicate that ordering depends on.
 */
class BlocklistTest {
    private fun session() =
        ListenTogetherSession(
            ListenTogetherClient(
                clientVersion = "blocklist-test",
                userAgent = "SimpJam/test (org.simpmusic.test; JVM)",
                serverUrl = { "ws://127.0.0.1:1/ws" }, // never connected in this test
            ),
        )

    @Test
    fun emptyBlocklistBlocksNobody() {
        val s = session()
        assertFalse(s.isBlocked("Anyone"), "default blocklist must be empty")
        assertFalse(s.isBlocked(""))
    }

    @Test
    fun matchesExactName() {
        val s = session()
        s.blockedNames = setOf("Eve")
        assertTrue(s.isBlocked("Eve"))
        assertFalse(s.isBlocked("Mallory"), "an unblocked name must pass")
    }

    @Test
    fun matchingIsCaseInsensitive() {
        val s = session()
        s.blockedNames = setOf("Eve")
        assertTrue(s.isBlocked("eve"), "case must not matter")
        assertTrue(s.isBlocked("EVE"))
        assertTrue(s.isBlocked("EvE"))
    }

    @Test
    fun matchesAnyEntryInASetOfMany() {
        val s = session()
        s.blockedNames = setOf("alice", "Bob", "troll_42")
        assertTrue(s.isBlocked("ALICE"))
        assertTrue(s.isBlocked("bob"))
        assertTrue(s.isBlocked("troll_42"))
        assertFalse(s.isBlocked("charlie"))
    }

    @Test
    fun isNotSubstringMatch() {
        // A blocked "Eve" must not also block "Evelyn" — matching is whole-name, not contains.
        val s = session()
        s.blockedNames = setOf("Eve")
        assertFalse(s.isBlocked("Evelyn"))
        assertFalse(s.isBlocked("Steve"))
    }
}
