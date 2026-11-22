package io.github.daniele21.localllm.transport.binder.contract

import io.github.daniele21.localllm.contracts.GenerationContentType
import io.github.daniele21.localllm.contracts.GenerationEvent
import io.github.daniele21.localllm.contracts.ModelDigest
import io.github.daniele21.localllm.contracts.RequestId

fun GenerationEvent.toWire(externalRequestId: String, sequence: Long): GenerationEventParcel {
    val result = toWireUnchecked(externalRequestId, sequence)
    validateGenerationEvent(result)
    return result
}

class GenerationEventReconstructor(private val externalRequestId: String, private val internalRequestId: RequestId) {
    private var nextSequence = 0L
    private var terminated = false
    private val reasoning = StringBuilder()
    private val answer = StringBuilder()

    fun accept(event: GenerationEventParcel): GenerationEvent {
        validateIncoming(event)
        val mapped = mapEvent(event)
        nextSequence += 1
        return mapped
    }

    private fun validateIncoming(event: GenerationEventParcel) {
        check(!terminated) { "Generation stream is already terminated" }
        validateGenerationEvent(event)
        requireWire(
            event.externalRequestId == externalRequestId,
            "Event request correlation does not match the active request",
            WireErrorCodes.TRANSPORT_FAILURE,
        )
        requireWire(
            event.sequence == nextSequence,
            "Generation event sequence is not contiguous",
            WireErrorCodes.TRANSPORT_FAILURE,
        )
    }

    private fun mapEvent(event: GenerationEventParcel): GenerationEvent = when (event.eventTag) {
        WireTags.EVENT_QUEUED -> GenerationEvent.Queued(internalRequestId, requireNotNull(event.queuePosition))

        WireTags.EVENT_PREPARED -> mapPrepared(event)

        WireTags.EVENT_STARTED ->
            GenerationEvent.Started(
                internalRequestId,
                ModelDigest(requireNotNull(event.modelDigestSha256)),
            )

        WireTags.EVENT_TEXT_DELTA -> mapDelta(event)

        WireTags.EVENT_COMPLETED -> mapCompleted(event)

        WireTags.EVENT_FAILED -> mapFailed(event)

        else -> throw invalidWireTag("generation event", event.eventTag)
    }

    private fun mapPrepared(event: GenerationEventParcel) = GenerationEvent.Prepared(
        internalRequestId,
        ModelDigest(requireNotNull(event.modelDigestSha256)),
        requireNotNull(event.preparedConfiguration).toCore(),
    )

    private fun mapDelta(event: GenerationEventParcel): GenerationEvent.TextDelta {
        val text = requireNotNull(event.deltaText)
        val contentType = event.contentTypeTag.toCoreContentType()
        when (contentType) {
            GenerationContentType.REASONING -> reasoning.append(text)
            GenerationContentType.ANSWER -> answer.append(text)
        }
        return GenerationEvent.TextDelta(
            requestId = internalRequestId,
            text = text,
            generatedTokens = requireNotNull(event.generatedTokens),
            contentType = contentType,
        )
    }

    private fun mapCompleted(event: GenerationEventParcel): GenerationEvent.Completed {
        terminated = true
        val answerText = answer.toString()
        return GenerationEvent.Completed(
            requestId = internalRequestId,
            output = answerText,
            metrics = requireNotNull(event.metrics).toCore(),
            reasoningOutput = reasoning.toString(),
            answerOutput = answerText,
        )
    }

    private fun mapFailed(event: GenerationEventParcel): GenerationEvent.Failed {
        terminated = true
        return GenerationEvent.Failed(internalRequestId, requireNotNull(event.error).toCore())
    }
}

fun chunkDelta(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + BinderProtocolV1.MAX_DELTA_CHARACTERS, text.length)
        if (wouldSplitSurrogatePair(text, start, end)) {
            end -= 1
        }
        if (end == start) {
            end = minOf(start + 2, text.length)
        }
        chunks += text.substring(start, end)
        start = end
    }
    return chunks
}

private fun GenerationEvent.toWireUnchecked(externalRequestId: String, sequence: Long): GenerationEventParcel = when (this) {
    is GenerationEvent.Queued ->
        GenerationEventParcel(
            externalRequestId = externalRequestId,
            sequence = sequence,
            eventTag = WireTags.EVENT_QUEUED,
            queuePosition = position,
        )

    is GenerationEvent.Prepared ->
        GenerationEventParcel(
            externalRequestId = externalRequestId,
            sequence = sequence,
            eventTag = WireTags.EVENT_PREPARED,
            modelDigestSha256 = modelDigest.sha256,
            preparedConfiguration = configuration.toWire(),
        )

    is GenerationEvent.Started ->
        GenerationEventParcel(
            externalRequestId = externalRequestId,
            sequence = sequence,
            eventTag = WireTags.EVENT_STARTED,
            modelDigestSha256 = modelDigest.sha256,
        )

    is GenerationEvent.VisionProgress ->
        error("Embedded vision progress is not supported by the text-only Binder protocol")

    is GenerationEvent.TextDelta -> {
        require(text.length <= BinderProtocolV1.MAX_DELTA_CHARACTERS) {
            "Delta must be chunked before Binder mapping"
        }
        GenerationEventParcel(
            externalRequestId = externalRequestId,
            sequence = sequence,
            eventTag = WireTags.EVENT_TEXT_DELTA,
            deltaText = text,
            generatedTokens = generatedTokens,
            contentTypeTag = contentType.toWireTag(),
        )
    }

    is GenerationEvent.Completed ->
        GenerationEventParcel(
            externalRequestId = externalRequestId,
            sequence = sequence,
            eventTag = WireTags.EVENT_COMPLETED,
            metrics = metrics.toWire(),
        )

    is GenerationEvent.Failed ->
        GenerationEventParcel(
            externalRequestId = externalRequestId,
            sequence = sequence,
            eventTag = WireTags.EVENT_FAILED,
            error = error.toSafeWire(),
        )
}

private fun wouldSplitSurrogatePair(text: String, start: Int, end: Int): Boolean {
    if (end >= text.length || end <= start) return false
    return Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])
}
