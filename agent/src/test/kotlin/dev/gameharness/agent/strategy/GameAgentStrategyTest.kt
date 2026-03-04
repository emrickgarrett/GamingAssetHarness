package dev.gameharness.agent.strategy

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

class GameAgentStrategyTest {

    @Test
    fun `gameAgentStrategy returns non-null strategy`() {
        val strategy = gameAgentStrategy()
        assertNotNull(strategy)
    }
}
