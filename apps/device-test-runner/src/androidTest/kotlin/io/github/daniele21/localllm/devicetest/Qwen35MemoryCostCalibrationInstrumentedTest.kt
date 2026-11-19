package io.github.daniele21.localllm.devicetest

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.daniele21.localllm.contracts.ModelDigest
import io.github.daniele21.localllm.models.ArtifactSource
import io.github.daniele21.localllm.models.GgufArtifact
import io.github.daniele21.localllm.models.GgufModelProfile
import io.github.daniele21.localllm.runtime.BackendContextConfiguration
import io.github.daniele21.localllm.runtime.BackendContextHandle
import io.github.daniele21.localllm.runtime.BackendGenerationOutcome
import io.github.daniele21.localllm.runtime.BackendGenerationRequest
import io.github.daniele21.localllm.runtime.BackendModelHandle
import io.github.daniele21.localllm.runtime.BackendModelSource
import io.github.daniele21.localllm.runtime.LlamaCppInferenceBackend
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * MEM-B cost evidence only. Exercises the production native backend directly to isolate
 * load/context/generation phases; it does not certify Harnex admission or device safety.
 */
@RunWith(AndroidJUnit4::class)
class Qwen35MemoryCostCalibrationInstrumentedTest {
    private val appContext: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun capturesNativeAllocationWindows() {
        val args = InstrumentationRegistry.getArguments()
        fun required(name: String) = requireNotNull(args.getString(name)?.takeIf(String::isNotBlank)) {
            "Missing calibration identity: " + name
        }
        val tier = required("tier")
        require(tier == "0.8b" || tier == "2b") { "Unsupported calibration tier" }
        val digest = ModelDigest(required("modelSha256").lowercase())
        val relativePath = required("modelRelativePath")
        require(!File(relativePath).isAbsolute) { "Model path must be relative" }
        val root = appContext.dataDir.canonicalFile
        val modelFile = File(root, relativePath).canonicalFile
        require(modelFile.path.startsWith(root.path + File.separator) && modelFile.isFile) {
            "Calibration model is unavailable or outside app storage"
        }
        require(sha256(modelFile) == digest.sha256) { "Model bytes differ from curated artifact identity" }
        require(Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") { "Physical ARM64 device required" }

        val contextTokens = required("contextTokens").toInt()
        require(contextTokens in listOf(1_024, 2_048, 4_096, 8_192)) { "Unsupported context tier" }
        val cpuThreads = required("cpuThreads").toInt()
        val batchThreads = required("batchThreads").toInt()
        require(cpuThreads > 0 && batchThreads > 0) { "CPU thread counts must be positive" }
        val outputTokens = required("outputTokens").toInt()
        require(outputTokens in 1..128) { "Calibration output token count must be bounded" }

        val profile = GgufModelProfile(
            id = required("runtimeProfileId"),
            artifact = GgufArtifact(
                digest = digest,
                fileName = modelFile.name,
                sizeBytes = modelFile.length(),
                architecture = "qwen35",
                quantization = "Q4_K_M",
                source = ArtifactSource.Imported("physical-memory-calibration"),
            ),
            contextSize = contextTokens,
            batchSize = 128,
            microBatchSize = 64,
            cpuThreads = cpuThreads,
            batchThreads = batchThreads,
            gpuLayers = 0,
            useMmap = true,
            useMlock = false,
            flashAttention = false,
        )
        val backend = LlamaCppInferenceBackend(File(appContext.applicationInfo.nativeLibraryDir))
        require(backend.revision == required("backendRevision")) { "Backend pin mismatch" }
        val recorder = NativeCostPhaseRecorder(appContext)
        var initialized = false
        var model: BackendModelHandle? = null
        var nativeContext: BackendContextHandle? = null
        try {
            recorder.point("cold_baseline")
            recorder.measure("backend_initialize") {
                backend.initialize()
                initialized = true
            }
            model = recorder.measure("model_load") {
                backend.loadModel(BackendModelSource(digest, modelFile, modelFile.length()), profile)
            }
            nativeContext = recorder.measure("context_create") {
                backend.createContext(requireNotNull(model), profile, BackendContextConfiguration(contextTokens))
            }
            val outcome = recorder.measure("generation") {
                backend.generate(
                    requireNotNull(nativeContext),
                    BackendGenerationRequest(
                        requestId = UUID.randomUUID().toString(),
                        prompt = "Reply with READY.",
                        maxOutputTokens = outputTokens,
                        temperature = 0f,
                        topP = 1f,
                        topK = 0,
                        repeatPenalty = 1f,
                        repeatLastN = 64,
                        seed = 42L,
                    ),
                ) { _, _ -> true }
            }
            assertTrue("Native generation must complete", outcome is BackendGenerationOutcome.Completed)
            recorder.measure("context_release") {
                backend.releaseContext(requireNotNull(nativeContext))
                nativeContext = null
            }
            recorder.measure("model_unload") {
                backend.unloadModel(requireNotNull(model))
                model = null
            }
            recorder.measure("backend_shutdown") {
                backend.shutdown()
                initialized = false
            }
            Thread.sleep(750)
            recorder.point("settled_after_unload")

            val evidence = JSONObject()
                .put("schemaVersion", 1)
                .put("status", "CANDIDATE_UNREVIEWED")
                .put("harnessCommit", required("harnessCommit"))
                .put("backendId", backend.id)
                .put("backendRevision", backend.revision)
                .put("modelDigest", digest.sha256)
                .put("tier", tier)
                .put("runtimeProfileId", profile.id)
                .put("runtimeProfileVersion", required("runtimeProfileVersion").toInt())
                .put("deviceModel", Build.MODEL)
                .put("deviceManufacturer", Build.MANUFACTURER)
                .put("deviceBuildFingerprint", Build.FINGERPRINT)
                .put("sdkInt", Build.VERSION.SDK_INT)
                .put("abi", Build.SUPPORTED_ABIS.first())
                .put("contextTokens", contextTokens)
                .put("cpuThreads", cpuThreads)
                .put("batchThreads", batchThreads)
                .put("batchSize", profile.batchSize)
                .put("microBatchSize", profile.microBatchSize)
                .put("gpuLayers", profile.gpuLayers)
                .put("useMmap", profile.useMmap)
                .put("useMlock", profile.useMlock)
                .put("flashAttention", profile.flashAttention)
                .put("kvCacheTypeK", profile.kvCacheTypeK ?: "default")
                .put("kvCacheTypeV", profile.kvCacheTypeV ?: "default")
                .put("outputTokens", outputTokens)
                .put("workload", "fixed-native-probe-v1")
                .put("sampleIntervalMs", 200)
                .put("phases", recorder.results())
            println("LOCAL_LLM_MEMORY_CALIBRATION_JSON " + evidence)
        } finally {
            nativeContext?.let { runCatching { backend.releaseContext(it) } }
            model?.let { runCatching { backend.unloadModel(it) } }
            if (initialized) runCatching(backend::shutdown)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(128 * 1_024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

private class NativeCostPhaseRecorder(context: Context) {
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val entries = JSONArray()

    fun point(name: String) {
        val current = snapshot()
        entries.put(phase(name, current, current, current.pssKb, current.availableBytes, 1, 0L))
    }

    fun <T> measure(name: String, block: () -> T): T {
        val before = snapshot()
        val running = AtomicBoolean(true)
        var peakPss = before.pssKb
        var minAvailable = before.availableBytes
        var sampleCount = 1
        var sampleFailure: Throwable? = null
        val watcher = thread(name = "native-cost-sampler", isDaemon = true) {
            while (running.get()) {
                try {
                    Thread.sleep(200)
                    if (!running.get()) break
                    val current = snapshot()
                    peakPss = maxOf(peakPss, current.pssKb)
                    minAvailable = listOfNotNull(minAvailable, current.availableBytes).minOrNull()
                    sampleCount++
                } catch (_: InterruptedException) {
                    break
                } catch (error: Throwable) {
                    sampleFailure = error
                    break
                }
            }
        }
        val started = System.nanoTime()
        try {
            return block()
        } finally {
            running.set(false)
            watcher.interrupt()
            watcher.join()
            val after = snapshot()
            entries.put(
                phase(
                    name = name,
                    before = before,
                    after = after,
                    peakPssKb = maxOf(peakPss, after.pssKb),
                    minimumAvailableBytes = listOfNotNull(minAvailable, after.availableBytes).minOrNull(),
                    samples = sampleCount + 1,
                    elapsedMs = (System.nanoTime() - started) / 1_000_000,
                ),
            )
            check(sampleFailure == null) { "Native memory sampling failed" }
        }
    }

    fun results(): JSONArray = entries

    private fun snapshot(): PhysicalMemoryPoint {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        val system = activityManager?.let { manager ->
            ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        }
        val javaRuntime = Runtime.getRuntime()
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) powerManager?.currentThermalStatus else null
        return PhysicalMemoryPoint(
            pssKb = info.totalPss,
            nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
            javaHeapBytes = javaRuntime.totalMemory() - javaRuntime.freeMemory(),
            availableBytes = system?.availMem,
            lowMemory = system?.lowMemory,
            thermalStatus = thermal,
        )
    }

    private fun phase(
        name: String,
        before: PhysicalMemoryPoint,
        after: PhysicalMemoryPoint,
        peakPssKb: Int,
        minimumAvailableBytes: Long?,
        samples: Int,
        elapsedMs: Long,
    ) = JSONObject()
        .put("phase", name)
        .put("samples", samples)
        .put("elapsedMs", elapsedMs)
        .put("pssBeforeKb", before.pssKb)
        .put("pssPeakObservedKb", peakPssKb)
        .put("pssAfterKb", after.pssKb)
        .put("nativeHeapBeforeBytes", before.nativeHeapBytes)
        .put("nativeHeapAfterBytes", after.nativeHeapBytes)
        .put("javaHeapBeforeBytes", before.javaHeapBytes)
        .put("javaHeapAfterBytes", after.javaHeapBytes)
        .put("availableBeforeBytes", before.availableBytes ?: JSONObject.NULL)
        .put("minimumAvailableBytes", minimumAvailableBytes ?: JSONObject.NULL)
        .put("availableAfterBytes", after.availableBytes ?: JSONObject.NULL)
        .put("lowMemoryBefore", before.lowMemory ?: JSONObject.NULL)
        .put("lowMemoryAfter", after.lowMemory ?: JSONObject.NULL)
        .put("thermalBefore", before.thermalStatus ?: JSONObject.NULL)
        .put("thermalAfter", after.thermalStatus ?: JSONObject.NULL)
}

private data class PhysicalMemoryPoint(
    val pssKb: Int,
    val nativeHeapBytes: Long,
    val javaHeapBytes: Long,
    val availableBytes: Long?,
    val lowMemory: Boolean?,
    val thermalStatus: Int?,
)
