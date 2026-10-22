package org.simpmusic.listentogether

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SimpJam end-to-end room test against a self-hosted metroserver.
 *
 * Unlike [LiveServerHandshakeTest] (which touches the public community server), this drives OUR
 * OWN relay over its Cloudflare tunnel and exercises a two-client room for real:
 *   host creates → guest joins → host approves → both land in the room
 *   → host issues a PLAY with a track → guest receives the synced playback action
 *
 * The server URL is injected via the `SIMPJAM_SERVER_URL` env var so the same test can point at
 * any deployment. This is a network test: not part of the default suite (run with -Pe2eServer set,
 * guarded below so CI without a server skips cleanly).
 */
class SimpJamRoomE2ETest {
    private val serverUrl: String? =
        System.getenv("SIMPJAM_SERVER_URL")?.takeIf { it.isNotBlank() }

    @Test
    fun twoClientsCreateJoinApproveAndSyncPlayback() =
        runBlocking {
            val serverUrl = serverUrl
            if (serverUrl == null) {
                println("SIMPJAM_SERVER_URL not set — skipping E2E (no server configured)")
                return@runBlocking
            }
            val host = ListenTogetherClient(
                clientVersion = "simpjam-e2e-host",
                userAgent = "SimpJam/e2e (org.simpmusic.test; JVM)",
                serverUrl = { serverUrl },
            )
            val guest = ListenTogetherClient(
                clientVersion = "simpjam-e2e-guest",
                userAgent = "SimpJam/e2e (org.simpmusic.test; JVM)",
                serverUrl = { serverUrl },
            )
            val hostEvents = Channel<ListenTogetherEvent>(Channel.UNLIMITED)
            val guestEvents = Channel<ListenTogetherEvent>(Channel.UNLIMITED)
            // runBlocking waits for ALL children — collectors on a never-completing SharedFlow
            // would hang the test forever, so keep the Jobs and cancel them in `finally`.
            val hostCollector = launch { host.events.collect { hostEvents.send(it) } }
            val guestCollector = launch { guest.events.collect { guestEvents.send(it) } }

            suspend fun await(
                ch: Channel<ListenTogetherEvent>,
                label: String,
                predicate: (ListenTogetherEvent) -> Boolean,
            ): ListenTogetherEvent? =
                withTimeoutOrNull(20_000) {
                    for (event in ch) {
                        println("  ← $label: $event")
                        if (predicate(event)) return@withTimeoutOrNull event
                    }
                    null
                }.also { if (it == null) println("  ✗ $label timed out") }

            try {
                delay(200)
                println("→ host connecting to $serverUrl")
                host.connect()
                assertNotNull(
                    await(hostEvents, "host-connected") { it is ListenTogetherEvent.Connected },
                    "host handshake failed",
                )

                println("→ host create_room")
                assertTrue(host.send(MessageTypes.CREATE_ROOM, CreateRoomPayload(username = "E2EHost")))
                val created = await(hostEvents, "room_created") {
                    it is ListenTogetherEvent.Message && it.type == MessageTypes.ROOM_CREATED
                } as? ListenTogetherEvent.Message
                val roomPayload = created?.payload as? RoomCreatedPayload
                assertNotNull(roomPayload, "no room_created")
                val roomCode = roomPayload.roomCode
                println("✓ room created: $roomCode")
                assertTrue(roomCode.isNotBlank(), "empty room code")

                println("→ guest connecting")
                guest.connect()
                assertNotNull(
                    await(guestEvents, "guest-connected") { it is ListenTogetherEvent.Connected },
                    "guest handshake failed",
                )
                println("→ guest join_room $roomCode")
                assertTrue(guest.send(MessageTypes.JOIN_ROOM, JoinRoomPayload(roomCode = roomCode, username = "E2EGuest")))

                // Host sees a join_request and approves it.
                val joinReq = await(hostEvents, "join_request") {
                    it is ListenTogetherEvent.Message && it.type == MessageTypes.JOIN_REQUEST
                } as? ListenTogetherEvent.Message
                val joinReqPayload = joinReq?.payload as? JoinRequestPayload
                assertNotNull(joinReqPayload, "host never saw join_request")
                println("→ host approve_join ${joinReqPayload.userId}")
                assertTrue(host.send(MessageTypes.APPROVE_JOIN, ApproveJoinPayload(userId = joinReqPayload.userId)))

                val approved = await(guestEvents, "join_approved") {
                    it is ListenTogetherEvent.Message && it.type == MessageTypes.JOIN_APPROVED
                }
                assertNotNull(approved, "guest never got join_approved")
                println("✓ guest joined room $roomCode")

                // Host sets the current track first (change_track), then issues PLAY.
                // metroserver requires CurrentTrack to exist before it accepts a play action.
                val track = TrackInfo(id = "e2e-track-1", title = "SimpJam Test Tone", artist = "ENI", duration = 180_000L)
                println("→ host change_track ${track.title}")
                assertTrue(host.send(MessageTypes.PLAYBACK_ACTION, PlaybackActionPayload(
                    action = "change_track", trackId = track.id, position = 0L, trackInfo = track,
                )))
                // Give the server a moment to apply the track change before PLAY.
                delay(1_500)

                println("→ host PLAY ${track.title}")
                assertTrue(host.send(MessageTypes.PLAYBACK_ACTION, PlaybackActionPayload(
                    action = "play", trackId = track.id, position = 0L, trackInfo = track,
                )))

                val synced = await(guestEvents, "sync_playback") {
                    it is ListenTogetherEvent.Message &&
                        (it.type == MessageTypes.SYNC_PLAYBACK || it.type == MessageTypes.PLAYBACK_ACTION) &&
                        (it.payload as? PlaybackActionPayload)?.action == "play"
                } as? ListenTogetherEvent.Message
                val playback = synced?.payload as? PlaybackActionPayload
                assertNotNull(playback, "guest never received the synced PLAY action")
                assertEquals(track.id, playback.trackId, "guest got the wrong track")
                println("✓ guest received synced PLAY for '${playback.trackInfo?.title ?: track.id}' at pos ${playback.position}ms")
                println("=== SimpJam E2E PASSED: create → join → approve → playback sync all green ===")
            } finally {
                hostCollector.cancel()
                guestCollector.cancel()
                host.disconnect()
                guest.disconnect()
            }
        }
}
