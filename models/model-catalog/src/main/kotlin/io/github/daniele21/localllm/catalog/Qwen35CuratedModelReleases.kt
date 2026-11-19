package io.github.daniele21.localllm.catalog

internal object Qwen35CuratedModelReleases {
    val releases: List<CatalogModelRelease> =
        listOf(
            qwenThreePointFiveZeroPointEightBQFourKM(),
            qwenThreePointFiveZeroPointEightBQFiveKM(),
            qwenThreePointFiveZeroPointEightBQEightZero(),
            qwenThreePointFiveZeroPointEightBUdIqTwoXxs(),
            qwenThreePointFiveTwoBQFourKM(),
            qwenThreePointFiveTwoBQFiveKM(),
            qwenThreePointFiveTwoBUdIqTwoXxs(),
        )

    private fun qwenThreePointFiveZeroPointEightBQFourKM(): CatalogModelRelease = CuratedModelReleaseFactory.release(
        modelId = "qwen35-08b-q4-k-m",
        displayName = "Qwen 3.5 0.8B Q4_K_M",
        description =
        "Modello Qwen ultra-compatto, consigliato come default leggero per classificazione, estrazione strutturata e generazione breve.",
        downloadUrl =
        "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/5aea8824cba95d22990acc6ea66c2c1909530650/Qwen3.5-0.8B-Q4_K_M.gguf?download=true",
        sha256 = "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517",
        sizeBytes = 532_517_120,
        fileName = "Qwen3.5-0.8B-Q4_K_M.gguf",
        architecture = "qwen35",
        quantization = "Q4_K_M",
        minRamBytes = 3_000_000_000,
        recommendedRamBytes = 4_000_000_000,
        profileKey = "qwen35-08b-q4-k-m-ctx4096",
        licenseId = "Apache-2.0",
        sourceUrl = "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF",
        useCases =
        setOf(
            "transaction-classification",
            "intent-classification",
            "structured-extraction",
            "short-summarization",
        ),
    )

    private fun qwenThreePointFiveZeroPointEightBQFiveKM(): CatalogModelRelease = CuratedModelReleaseFactory.release(
        modelId = "qwen35-08b-q5-k-m",
        displayName = "Qwen 3.5 0.8B Q5_K_M",
        description =
        "Versione a maggiore precisione del modello Qwen 0.8B, indicata quando l'affidabilità dell'output è più importante del minimo consumo di memoria.",
        downloadUrl =
        "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/c61c105fd9f6919021a9f13e7d98f11edfddc41e/Qwen3.5-0.8B-Q5_K_M.gguf?download=true",
        sha256 = "c3ef5827b322c4be08a3a26ce424460d8b37daf593356c2cc1f4e40a7ab0581b",
        sizeBytes = 590_057_728,
        fileName = "Qwen3.5-0.8B-Q5_K_M.gguf",
        architecture = "qwen35",
        quantization = "Q5_K_M",
        minRamBytes = 3_000_000_000,
        recommendedRamBytes = 4_000_000_000,
        profileKey = "qwen35-08b-q5-k-m-ctx4096",
        licenseId = "Apache-2.0",
        sourceUrl = "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF",
        useCases =
        setOf(
            "transaction-classification",
            "intent-classification",
            "structured-extraction",
            "short-summarization",
        ),
    )

    private fun qwenThreePointFiveZeroPointEightBQEightZero(): CatalogModelRelease = CuratedModelReleaseFactory.release(
        modelId = "qwen35-08b-q8-0",
        displayName = "Qwen 3.5 0.8B Q8_0",
        description =
        "Versione ad alta precisione del modello Qwen 0.8B, utile come riferimento qualitativo nei benchmark delle quantizzazioni inferiori.",
        downloadUrl =
        "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/e524882462b3f2a9fe83be967c654c4322abb2f6/Qwen3.5-0.8B-Q8_0.gguf?download=true",
        sha256 = "0ad885ffd4bb022fc4f0d33a3308fa108ef8613159d3b3a67e23abca056b7a6c",
        sizeBytes = 811_843_840,
        fileName = "Qwen3.5-0.8B-Q8_0.gguf",
        architecture = "qwen35",
        quantization = "Q8_0",
        minRamBytes = 4_000_000_000,
        recommendedRamBytes = 6_000_000_000,
        profileKey = "qwen35-08b-q8-0-ctx4096",
        licenseId = "Apache-2.0",
        sourceUrl = "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF",
        useCases =
        setOf(
            "quantization-benchmark",
            "transaction-classification",
            "structured-extraction",
            "short-summarization",
        ),
    )

    private fun qwenThreePointFiveZeroPointEightBUdIqTwoXxs(): CatalogModelRelease = CuratedModelReleaseFactory.release(
        modelId = "qwen35-08b-ud-iq2-xxs",
        displayName = "Qwen 3.5 0.8B UD-IQ2_XXS",
        description =
        "Quantizzazione minima di Qwen 3.5 0.8B, consigliata come modello ultra-leggero per sanity test, verifica del runtime, streaming, cancellazione e benchmark rapidi di inferenza locale.",
        downloadUrl =
        "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/5aea8824cba95d22990acc6ea66c2c1909530650/Qwen3.5-0.8B-UD-IQ2_XXS.gguf?download=true",
        sha256 = "a369165c8ec45a92d55cbad98b5377e2c32ca8dfc824c899d9d05b33b6b53e54",
        sizeBytes = 338_227_456,
        fileName = "Qwen3.5-0.8B-UD-IQ2_XXS.gguf",
        architecture = "qwen35",
        quantization = "UD-IQ2_XXS",
        minRamBytes = 3_000_000_000,
        recommendedRamBytes = 4_000_000_000,
        profileKey = "qwen35-08b-ud-iq2-xxs-ctx2048",
        licenseId = "Apache-2.0",
        sourceUrl = "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF",
        useCases =
        setOf(
            "runtime-sanity",
            "runtime-smoke-test",
            "streaming-test",
            "cancellation-test",
            "lightweight-inference",
            "quantization-benchmark",
        ),
    )

    private fun qwenThreePointFiveTwoBQFourKM(): CatalogModelRelease = CuratedModelReleaseFactory.release(
        modelId = "qwen35-2b-q4-k-m",
        displayName = "Qwen 3.5 2B Q4_K_M",
        description =
        "Modello Qwen di fascia intermedia, consigliato come default di qualità sui dispositivi Android con almeno 6-8 GB di RAM.",
        downloadUrl =
        "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/802854bfd388ed92748de119df31327962811548/Qwen3.5-2B-Q4_K_M.gguf?download=true",
        sha256 = "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223",
        sizeBytes = 1_280_835_840,
        fileName = "Qwen3.5-2B-Q4_K_M.gguf",
        architecture = "qwen35",
        quantization = "Q4_K_M",
        minRamBytes = 6_000_000_000,
        recommendedRamBytes = 8_000_000_000,
        profileKey = "qwen35-2b-q4-k-m-ctx4096",
        licenseId = "Apache-2.0",
        sourceUrl = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF",
        useCases =
        setOf(
            "transaction-classification",
            "structured-extraction",
            "summarization",
            "local-assistant",
            "local-rag",
        ),
        allowEmbeddedLocalTextGeneration = true,
    )

    private fun qwenThreePointFiveTwoBQFiveKM(): CatalogModelRelease = CuratedModelReleaseFactory.release(
        modelId = "qwen35-2b-q5-k-m",
        displayName = "Qwen 3.5 2B Q5_K_M",
        description =
        "Versione a maggiore precisione del modello Qwen 2B, indicata per estrazione strutturata e classificazioni in cui è richiesta maggiore affidabilità.",
        downloadUrl =
        "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/7938c998e44eb9f079e01a966c2d1431c6b8ef16/Qwen3.5-2B-Q5_K_M.gguf?download=true",
        sha256 = "1885b3a9195f8cc09da9a7a7a75afdc1e8d5cbf9fc4a499c3961dddea37098ac",
        sizeBytes = 1_435_238_656,
        fileName = "Qwen3.5-2B-Q5_K_M.gguf",
        architecture = "qwen35",
        quantization = "Q5_K_M",
        minRamBytes = 6_000_000_000,
        recommendedRamBytes = 8_000_000_000,
        profileKey = "qwen35-2b-q5-k-m-ctx4096",
        licenseId = "Apache-2.0",
        sourceUrl = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF",
        useCases =
        setOf(
            "transaction-classification",
            "structured-extraction",
            "summarization",
            "local-assistant",
            "local-rag",
        ),
    )

    private fun qwenThreePointFiveTwoBUdIqTwoXxs(): CatalogModelRelease = CuratedModelReleaseFactory.release(
        modelId = "qwen35-2b-ud-iq2-xxs",
        displayName = "Qwen 3.5 2B UD-IQ2_XXS",
        description =
        "Quantizzazione minima di Qwen 3.5 2B, utile per verificare l'esecuzione di un modello intermedio mantenendo contenuti spazio, RAM e tempi di download.",
        downloadUrl =
        "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/a847ba37feb0735899f98cc9287bb84e432d7010/Qwen3.5-2B-UD-IQ2_XXS.gguf?download=true",
        sha256 = "43aedbd2b03a3c2cc39f49ccf74fcd3c394ed0b2a1ede8a30ee652ee9cfc27ef",
        sizeBytes = 768_270_592,
        fileName = "Qwen3.5-2B-UD-IQ2_XXS.gguf",
        architecture = "qwen35",
        quantization = "UD-IQ2_XXS",
        minRamBytes = 4_000_000_000,
        recommendedRamBytes = 6_000_000_000,
        profileKey = "qwen35-2b-ud-iq2-xxs-ctx2048",
        licenseId = "Apache-2.0",
        sourceUrl = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF",
        useCases =
        setOf(
            "runtime-regression-test",
            "memory-pressure-test",
            "lightweight-inference",
            "structured-extraction-test",
            "classification-test",
            "quantization-benchmark",
        ),
    )
}
