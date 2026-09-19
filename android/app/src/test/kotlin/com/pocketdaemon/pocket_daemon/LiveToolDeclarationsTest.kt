package com.pocketdaemon.pocket_daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * gemini-3.8-live defaults function calls to NON_BLOCKING, so every live declaration must state its
 * behavior, and async ones must carry the scheduling hint their responses are tagged with.
 */
class LiveToolDeclarationsTest {

    private val agentTypes = listOf("call", "trusted", "chat", "scheduled")

    @Test
    fun everyLiveDeclarationStatesABehavior() {
        for (agentType in agentTypes) {
            val specs = AgentToolRegistry.liveDeclarations("User", agentType)
            assertTrue("$agentType has tools", specs.isNotEmpty())
            for (spec in specs) {
                assertNotNull("${spec.name} ($agentType) has no behavior", spec.behavior)
                assertTrue(
                    "${spec.name} ($agentType) has unknown behavior ${spec.behavior}",
                    spec.behavior == ToolBehavior.BLOCKING || spec.behavior == ToolBehavior.NON_BLOCKING,
                )
            }
        }
    }

    @Test
    fun slowToolsAreNonBlockingWithSchedulingAndFastToolsBlock() {
        val chat = AgentToolRegistry.liveDeclarations("User", "chat").associateBy { it.name }

        for (name in listOf(
            AgentToolRegistry.ASK_EXPERT,
            AgentToolRegistry.ASK_FABLE,
            AgentToolRegistry.TAKE_PHOTO,
            AgentToolRegistry.USE_SKILL,
            AgentToolRegistry.GET_LOCATION,
        )) {
            val spec = chat.getValue(name)
            assertEquals(name, ToolBehavior.NON_BLOCKING, spec.behavior)
            assertTrue(name, spec.nonBlocking)
            assertEquals(name, ToolScheduling.WHEN_IDLE, spec.scheduling)
        }

        for (name in listOf(
            AgentToolRegistry.SEARCH_MEMORY,
            AgentToolRegistry.SEARCH_CONTACTS,
            AgentToolRegistry.DIAL_CONTACT,
            AgentToolRegistry.LEAVE_MESSAGE,
            AgentToolRegistry.END_SESSION,
        )) {
            val spec = chat.getValue(name)
            assertEquals(name, ToolBehavior.BLOCKING, spec.behavior)
            assertTrue(name, !spec.nonBlocking)
            assertNull(name, spec.scheduling)
        }
    }

    @Test
    fun hangUpBlocksOnCallTiers() {
        for (agentType in listOf("call", "trusted")) {
            val hangUp = AgentToolRegistry.liveDeclarations("User", agentType).first { it.name == AgentToolRegistry.HANG_UP }
            assertEquals(agentType, ToolBehavior.BLOCKING, hangUp.behavior)
        }
    }

    @Test
    fun googleSearchIsNeverAFunctionDeclaration() {
        for (agentType in agentTypes) {
            val names = AgentToolRegistry.liveDeclarations("User", agentType).map { it.name }
            assertTrue(agentType, AgentToolRegistry.GOOGLE_SEARCH !in names)
        }
    }
}
