package com.google.android.accessibility.selecttospeak

import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService

/**
 * Impersonates the official Google Select-to-Speak accessibility service
 * so vivo/OPPO/Xiaomi and similar ROM allowlists let it through.
 */
class SelectToSpeakService : AgentAccessibilityService()
