package org.helix.bridge.paper

import kotlin.test.Test
import kotlin.test.assertEquals

class ScoreboardPlaceholdersTest {
    private val global = ScoreboardPlaceholders.Global(
        online = 7,
        max = 100,
        server = "lobby-1",
        task = "lobby",
        tps = "20.0",
        date = "2026-07-27",
        time = "13:37",
        network = "Helix-Cloud",
        prefix = "&8[&bHC&8]",
    )

    @Test
    fun `global substitutes only the shared placeholders and leaves per-player ones untouched`() {
        val text = "{online}/{max} on {server} ({task}) at {tps} tps — {player} in {world}"

        val resolved = ScoreboardPlaceholders.global(text, global)

        assertEquals("7/100 on lobby-1 (lobby) at 20.0 tps — {player} in {world}", resolved)
    }

    @Test
    fun `player substitutes the remaining per-player placeholders on already globally-resolved text`() {
        val globallyResolved = ScoreboardPlaceholders.global("{online} online — {player} at {x},{y},{z}", global)
        val steve = ScoreboardPlaceholders.PerPlayer(
            name = "Steve",
            displayName = "&7Steve",
            nick = "Steve",
            ping = 42,
            world = "world",
            x = 10,
            y = 64,
            z = -3,
            balance = "500",
            clan = "STV",
        )

        assertEquals("7 online — Steve at 10,64,-3", ScoreboardPlaceholders.player(globallyResolved, steve))
    }

    @Test
    fun `one globally-resolved template renders correctly for every viewer`() {
        // Mirrors the refresh loop: the board is resolved against the shared
        // Global values exactly once, then the same resolved template is
        // rendered per player — the global segment must stay identical while
        // the per-player segment varies.
        val template = ScoreboardPlaceholders.global("&f{player}&7: &a{online} online, &e{balance} coins", global)

        val steve = ScoreboardPlaceholders.player(
            template,
            ScoreboardPlaceholders.PerPlayer(
                name = "Steve",
                displayName = "Steve",
                nick = "Steve",
                ping = 20,
                world = "world",
                x = 0,
                y = 0,
                z = 0,
                balance = "100",
                clan = "",
            ),
        )
        val alex = ScoreboardPlaceholders.player(
            template,
            ScoreboardPlaceholders.PerPlayer(
                name = "Alex",
                displayName = "Alex",
                nick = "Alex",
                ping = 30,
                world = "world",
                x = 0,
                y = 0,
                z = 0,
                balance = "250",
                clan = "",
            ),
        )

        assertEquals("&fSteve&7: &a7 online, &e100 coins", steve)
        assertEquals("&fAlex&7: &a7 online, &e250 coins", alex)
    }
}
