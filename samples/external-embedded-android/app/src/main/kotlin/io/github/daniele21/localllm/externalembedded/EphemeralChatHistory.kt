package io.github.daniele21.localllm.externalembedded

import io.github.daniele21.localllm.contracts.ConversationMessage
import io.github.daniele21.localllm.contracts.ConversationRole
import java.util.ArrayDeque

/**
 * Demo-only, in-memory conversation selection. Not an SDK/runtime/chat persistence owner.
 * Each generation receives a complete, bounded Messages snapshot; only completed turns
 * are retained. Restarting the app loses this history (the installed GGUF remains).
 */
internal class EphemeralChatHistory(
    private val maxTurns: Int = 6,
    private val maxContextChars: Int = 6_000,
) {
    private val turns = ArrayDeque<Pair<String, String>>()

    fun messagesFor(userText: String): List<ConversationMessage> {
        require(userText.isNotBlank() && userText.length <= MAX_USER_CHARS) {
            "Message must contain 1–$MAX_USER_CHARS characters"
        }
        val prior = ArrayDeque<Pair<String, String>>()
        var chars = userText.length
        for (turn in turns.reversed()) {
            val next = turn.first.length + turn.second.length
            if (chars + next > maxContextChars) break
            chars += next
            prior.addFirst(turn)
        }
        return buildList {
            for ((user, assistant) in prior) {
                add(ConversationMessage(ConversationRole.USER, user))
                add(ConversationMessage(ConversationRole.ASSISTANT, assistant))
            }
            add(ConversationMessage(ConversationRole.USER, userText))
        }
    }

    fun complete(userText: String, answer: String) {
        require(userText.isNotBlank() && answer.isNotBlank())
        turns.addLast(userText to answer)
        while (turns.size > maxTurns) turns.removeFirst()
    }

    fun reset() = turns.clear()

    fun completedTurns(): Int = turns.size

    companion object {
        const val MAX_USER_CHARS = 600
    }
}
