package dev.gameharness.agent.strategy

import ai.koog.agents.core.agent.singleRunStrategy

/**
 * Returns the KOOG execution strategy used by [GameAgent][dev.gameharness.agent.GameAgent].
 *
 * Uses KOOG's built-in single-run strategy: one LLM request with an iterative
 * tool-call loop, producing a final text response. Each call to `agent.run()`
 * processes exactly one user turn.
 */
fun gameAgentStrategy() = singleRunStrategy()
