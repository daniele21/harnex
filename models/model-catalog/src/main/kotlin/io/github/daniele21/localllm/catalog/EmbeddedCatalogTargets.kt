package io.github.daniele21.localllm.catalog

import io.github.daniele21.localllm.contracts.ApplicationId
import io.github.daniele21.localllm.contracts.UseCaseId

/**
 * Model-catalog target for inference inside the installing Android application's sandbox.
 * Unlike Shared Host authorization, this is a local installation eligibility policy only.
 */
object EmbeddedCatalogTargets {
    val LOCAL_TEXT_GENERATION: CatalogTarget =
        CatalogTarget(ApplicationId("harnex-embedded"), UseCaseId("local-text-generation"))
}
