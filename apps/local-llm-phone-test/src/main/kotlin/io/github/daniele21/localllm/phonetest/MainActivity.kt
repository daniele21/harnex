@file:Suppress("FunctionName")

package io.github.daniele21.localllm.phonetest

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.daniele21.localllm.observability.android.AndroidResourceSnapshotProvider
import io.github.daniele21.localllm.observability.android.ResourceSnapshotRecorder
import io.github.daniele21.localllm.ui.designsystem.HarnessCard
import io.github.daniele21.localllm.ui.designsystem.HarnessColors
import io.github.daniele21.localllm.ui.designsystem.HarnessMetric
import io.github.daniele21.localllm.ui.designsystem.HarnessMetricRow
import io.github.daniele21.localllm.ui.designsystem.HarnessPrimaryButton
import io.github.daniele21.localllm.ui.designsystem.HarnessSecondaryButton
import io.github.daniele21.localllm.ui.designsystem.HarnessStatusBadge
import io.github.daniele21.localllm.ui.designsystem.HarnessStatusTone
import io.github.daniele21.localllm.ui.designsystem.HarnessTheme
import io.github.daniele21.localllm.ui.designsystem.harnessColorScheme
import java.util.concurrent.Executors

@Suppress("TooManyFunctions", "LongMethod", "LargeClass")
class MainActivity :
    ComponentActivity(),
    PhoneTestListener {
    private lateinit var runtimeGraph: HarnessRuntimeGraph
    private lateinit var diagnosticsSource: HarnessDiagnosticsSource
    private lateinit var healthSource: HarnessHealthSource
    private lateinit var resourceSource: HarnessResourceSource
    private lateinit var benchmarkSource: HarnessBenchmarkSource
    private lateinit var logSource: HarnessLogSource
    private lateinit var controller: PhoneTestController
    private lateinit var modelDistributionController: PhoneModelDistributionController
    private lateinit var selectedModelManagement: PhoneModelManagementGateway
    private lateinit var playgroundController: PhonePlaygroundController
    private lateinit var themePreferenceStore: HarnessThemePreferenceStore
    private lateinit var applicationsGateway: HarnessApplicationsGateway
    private val diagnosticsExecutor = Executors.newSingleThreadExecutor()
    private val harnessViewModel: HarnessViewModel by viewModels()
    private val performanceViewModel: PerformanceViewModel by viewModels()
    private val applicationsViewModel: HarnessApplicationsReadViewModel by viewModels()

    private var latestReport: String
        get() = harnessViewModel.uiState.value.latestReport
        set(value) = harnessViewModel.dispatch(HarnessUiEvent.ReportChanged(value))

    private var controllerBusy: Boolean
        get() = harnessViewModel.uiState.value.controllerBusy
        set(value) = harnessViewModel.dispatch(HarnessUiEvent.ControllerBusyChanged(value))

    private var operationStatus: String
        get() = harnessViewModel.uiState.value.operationStatus
        set(value) = harnessViewModel.dispatch(HarnessUiEvent.OperationStatusChanged(value))

    private var diagnosticsState: DiagnosticsUiState
        get() = harnessViewModel.uiState.value.diagnostics
        set(value) = harnessViewModel.dispatch(HarnessUiEvent.DiagnosticsChanged(value))

    private var benchmarkState: BenchmarkUiState
        get() = harnessViewModel.uiState.value.benchmark
        set(value) = harnessViewModel.dispatch(HarnessUiEvent.BenchmarkChanged(value))

    private val logFilter: DiagnosticsLogFilter
        get() = harnessViewModel.uiState.value.logFilter

    private var logState: DiagnosticsLogUiState
        get() = harnessViewModel.uiState.value.logs
        set(value) = harnessViewModel.dispatch(HarnessUiEvent.LogsChanged(value))

    private var selectedRequestTimeline: DiagnosticsRequestTimelineUi?
        get() = harnessViewModel.uiState.value.selectedRequestTimeline
        set(value) = harnessViewModel.dispatch(HarnessUiEvent.RequestTimelineChanged(value))

    private var diagnosticsSection: DiagnosticsSection
        get() = harnessViewModel.uiState.value.diagnosticsSection
        set(value) = harnessViewModel.dispatch(HarnessUiEvent.DiagnosticsSectionChanged(value))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themePreferenceStore = HarnessThemePreferenceStore(this)
        harnessViewModel.updateThemePreference(themePreferenceStore.read())
        latestReport = savedInstanceState?.getString(STATE_REPORT).orEmpty()
        runtimeGraph = HarnessRuntimeGraph.from(this)
        applicationsGateway = StoreHarnessCustomPresetGateway(runtimeGraph.controlPlaneStore)
        applicationsViewModel.attach(applicationsGateway)
        resourceSource = HarnessResourceSource(
            recorder = ResourceSnapshotRecorder(
                AndroidResourceSnapshotProvider(this),
                runtimeGraph.telemetryRepository,
            ),
            telemetryRepository = runtimeGraph.telemetryRepository,
        )
        diagnosticsSource = HarnessDiagnosticsSource(
            telemetryRepository = runtimeGraph.telemetryRepository,
            runtimeSnapshot = runtimeGraph::runtimeSnapshot,
            resourceSnapshots = resourceSource::recent,
        )
        healthSource = HarnessHealthSource(
            modelStore = runtimeGraph.modelStore,
            telemetryRepository = runtimeGraph.telemetryRepository,
            selectedModel = { harnessViewModel.uiState.value.importedModel },
            runtimeState = { runtimeGraph.runtimeSnapshot()?.state },
        )
        benchmarkSource = HarnessBenchmarkSource(runtimeGraph.telemetryRepository) {
            harnessViewModel.uiState.value.importedModel
        }
        logSource = HarnessLogSource(runtimeGraph.telemetryRepository)
        controller = PhoneTestController(this, this)
        selectedModelManagement = ModelStorePhoneModelManagementControl(
            modelStore = runtimeGraph.modelStore,
            protectedModelDigest = { runtimeGraph.loadedModelDigest },
            removeMetadata = { true },
        )
        modelDistributionController = PhoneModelDistributionController.from(
            context = this,
            runtimeGraph = runtimeGraph,
            listener = PhoneModelDistributionListener { state ->
                runOnUiThread {
                    operationStatus = state.message
                    harnessViewModel.dispatch(HarnessUiEvent.ModelDistributionChanged(state))
                    updateKeepScreenOn()
                }
            },
        )
        playgroundController = PhonePlaygroundController(runtimeGraph, ::onPlaygroundStateChanged)
        harnessViewModel.attachPlaygroundEffects(playgroundController)
        refreshDiagnostics()

        setContent {
            val uiState by harnessViewModel.uiState.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (uiState.themePreference) {
                HarnessThemePreference.DARK -> true
                HarnessThemePreference.LIGHT -> false
                HarnessThemePreference.SYSTEM -> systemDark
            }
            val colorScheme = harnessColorScheme(darkTheme)
            SideEffect {
                @Suppress("DEPRECATION")
                window.statusBarColor = colorScheme.background.toArgb()
                @Suppress("DEPRECATION")
                window.navigationBarColor = colorScheme.background.toArgb()
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            HarnessTheme(darkTheme = darkTheme) { HarnessApp() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_REPORT, latestReport)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::applicationsGateway.isInitialized) applicationsViewModel.detach(applicationsGateway)
        harnessViewModel.invalidateDiagnosticActions()
        diagnosticsExecutor.shutdownNow()
        modelDistributionController.close()
        harnessViewModel.detachPlaygroundEffects(playgroundController)
        playgroundController.close()
        controller.close()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    override fun onBusyChanged(busy: Boolean) {
        runOnUiThread {
            controllerBusy = busy
            updateKeepScreenOn()
            refreshDiagnostics()
        }
    }

    override fun onProgress(message: String) {
        runOnUiThread {
            operationStatus = message
            syncLoadedModelOwnership()
            refreshDiagnostics()
        }
    }

    override fun onModelChanged(model: ImportedPhoneModel?) {
        runOnUiThread {
            harnessViewModel.dispatch(HarnessUiEvent.ModelChanged(model))
            syncLoadedModelOwnership()
            if (::modelDistributionController.isInitialized) {
                modelDistributionController.refresh()
            }
            refreshDiagnostics()
        }
    }

    override fun onReport(report: String) {
        runOnUiThread {
            latestReport = report
            syncLoadedModelOwnership()
            operationStatus = "Validation completed"
            refreshDiagnostics()
        }
    }

    private fun onPlaygroundStateChanged(state: PlaygroundState) {
        runOnUiThread {
            harnessViewModel.dispatch(HarnessUiEvent.PlaygroundChanged(state))
            syncLoadedModelOwnership()
            updateKeepScreenOn()
            refreshDiagnostics()
        }
    }

    private fun verifySelectedModel(): Boolean {
        val model = harnessViewModel.uiState.value.importedModel ?: return false
        if (isBusy()) return false
        operationStatus = "Verifying selected model integrity…"
        diagnosticsExecutor.execute {
            val outcome = selectedModelManagement.verify(model.digest)
            runOnUiThread {
                operationStatus = outcome.detail
                modelDistributionController.refresh()
                refreshDiagnostics()
            }
        }
        return true
    }

    private fun refreshDiagnostics() {
        if (::diagnosticsSource.isInitialized) diagnosticsState = diagnosticsSource.snapshot()
        if (::resourceSource.isInitialized) {
            harnessViewModel.dispatch(HarnessUiEvent.ResourceHistoryChanged(resourceSource.history()))
        }
        if (::benchmarkSource.isInitialized) {
            benchmarkState = benchmarkSource.snapshot(benchmarkState.captureDetail)
        }
        if (::logSource.isInitialized) {
            logState = logSource.snapshot(logFilter)
            selectedRequestTimeline = selectedRequestTimeline?.let { timeline ->
                logSource.requestTimeline(timeline.requestId)
            }
        }
    }

    private fun runHealthChecks() {
        if (diagnosticActionRunning() || isBusy()) return
        val token = harnessViewModel.beginDiagnosticAction(HarnessDiagnosticAction.HEALTH)
        operationStatus = "Running health checks…"
        diagnosticsExecutor.execute {
            val result = runCatching { healthSource.runAll() }
            runOnUiThread {
                if (!harnessViewModel.finishDiagnosticAction(token)) return@runOnUiThread
                operationStatus = result.fold(
                    onSuccess = { "Health checks completed: ${it.status.name}" },
                    onFailure = { "Health checks could not be completed" },
                )
                refreshDiagnostics()
            }
        }
    }

    private fun captureResourceSnapshot() {
        if (diagnosticActionRunning() || isBusy()) return
        val token = harnessViewModel.beginDiagnosticAction(HarnessDiagnosticAction.RESOURCE_CAPTURE)
        operationStatus = "Capturing device resources…"
        diagnosticsExecutor.execute {
            val result = runCatching(resourceSource::capture)
            runOnUiThread {
                if (!harnessViewModel.finishDiagnosticAction(token)) return@runOnUiThread
                operationStatus = if (result.isSuccess) {
                    "Resource snapshot captured"
                } else {
                    "Resource snapshot could not be captured"
                }
                refreshDiagnostics()
            }
        }
    }

    private fun captureBenchmarkBaselines() {
        if (diagnosticActionRunning() || isBusy()) return
        val token = harnessViewModel.beginDiagnosticAction(HarnessDiagnosticAction.BENCHMARK_CAPTURE)
        operationStatus = "Capturing benchmark baselines…"
        diagnosticsExecutor.execute {
            val result = runCatching(benchmarkSource::captureEligible)
            runOnUiThread {
                if (!harnessViewModel.finishDiagnosticAction(token)) return@runOnUiThread
                benchmarkState = result.getOrElse {
                    BenchmarkUiState(sourceError = "Benchmark capture could not be completed.")
                }
                operationStatus = benchmarkState.captureDetail
                    ?: benchmarkState.sourceError
                    ?: "Benchmark capture completed"
                refreshDiagnostics()
            }
        }
    }

    private fun captureBenchmarkBaseline(stableId: String) {
        if (diagnosticActionRunning() || isBusy()) return
        val token = harnessViewModel.beginDiagnosticAction(HarnessDiagnosticAction.BENCHMARK_CAPTURE)
        operationStatus = "Capturing selected benchmark baseline…"
        diagnosticsExecutor.execute {
            val result = runCatching { benchmarkSource.capture(stableId) }
            runOnUiThread {
                if (!harnessViewModel.finishDiagnosticAction(token)) return@runOnUiThread
                benchmarkState = result.getOrElse {
                    BenchmarkUiState(sourceError = "Benchmark capture could not be completed.")
                }
                operationStatus = benchmarkState.captureDetail
                    ?: benchmarkState.sourceError
                    ?: "Benchmark capture completed"
                refreshDiagnostics()
            }
        }
    }

    private fun selectDiagnosticsSection(section: DiagnosticsSection) {
        diagnosticsSection = section
    }

    private fun updateLogFilter(filter: DiagnosticsLogFilter) {
        harnessViewModel.dispatch(
            HarnessUiEvent.LogFilterChanged(filter, logSource.snapshot(filter)),
        )
    }

    private fun openRequestTimeline(requestId: String) {
        selectedRequestTimeline = logSource.requestTimeline(requestId)
        diagnosticsSection = DiagnosticsSection.LOGS
    }

    private fun closeRequestTimeline() {
        selectedRequestTimeline = null
    }

    private fun updateThemePreference(preference: HarnessThemePreference) {
        themePreferenceStore.write(preference)
        harnessViewModel.updateThemePreference(preference)
    }

    @Composable
    private fun HarnessApp() {
        val uiState by harnessViewModel.uiState.collectAsStateWithLifecycle()
        val performanceState by performanceViewModel.state.collectAsStateWithLifecycle()
        val applicationsState by applicationsViewModel.state.collectAsStateWithLifecycle()
        val applicationsMutationState by applicationsViewModel.mutationState.collectAsStateWithLifecycle()
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val shellState = HarnessRoutes.shellState(backStackEntry?.destination?.route)
        val destination = shellState.destination
        val configuration = LocalConfiguration.current
        val adaptivePolicy = harnessAdaptivePolicy(
            widthDp = configuration.screenWidthDp,
            fontScale = configuration.fontScale,
        )
        val expanded = adaptivePolicy.useNavigationRail
        val modelEffects = remember { createModelEffects() }
        DisposableEffect(modelEffects) {
            harnessViewModel.models.attach(modelEffects)
            onDispose { harnessViewModel.models.detach(modelEffects) }
        }
        val navigate: (HarnessDestination) -> Unit = { target ->
            if (target == HarnessDestination.DIAGNOSTICS) {
                selectDiagnosticsSection(DiagnosticsSection.OVERVIEW)
            }
            navController.navigate(target.route) {
                launchSingleTop = true
                restoreState = true
                popUpTo(HarnessDestination.OVERVIEW.route) { saveState = true }
            }
        }
        val navigateToSettings: () -> Unit = {
            navController.navigate(HarnessDestination.SETTINGS.route) {
                launchSingleTop = true
            }
        }

        Scaffold(
            topBar = {
                if (shellState.isDetail) {
                    HarnessDetailTopBar(
                        title = requireNotNull(shellState.detailTitle),
                        subtitle = shellState.detailSubtitle.orEmpty(),
                        onNavigateBack = { navController.popBackStack() },
                    )
                } else {
                    HarnessTopBar(
                        destination = destination,
                        onOpenSettings = navigateToSettings,
                        onNavigateBack = {
                            if (!navController.popBackStack()) navigate(HarnessDestination.OVERVIEW)
                        },
                    )
                }
            },
            bottomBar = {
                if (!expanded && shellState.showBottomNavigation) {
                    HarnessBottomBar(destination = destination, onNavigate = navigate)
                }
            },
        ) { padding ->
            Row(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (expanded) {
                    NavigationRail(modifier = Modifier.fillMaxHeight()) {
                        HarnessDestination.main.forEach { item ->
                            NavigationRailItem(
                                modifier = Modifier.testTag("nav-${item.route}"),
                                selected = destination == item,
                                onClick = { navigate(item) },
                                icon = { HarnessDestinationIcon(item, selected = destination == item) },
                                label = { Text(item.label) },
                            )
                        }
                        NavigationRailItem(
                            selected = destination == HarnessDestination.SETTINGS,
                            onClick = navigateToSettings,
                            icon = {
                                HarnessDestinationIcon(
                                    HarnessDestination.SETTINGS,
                                    selected = destination == HarnessDestination.SETTINGS,
                                )
                            },
                            label = { Text("Settings") },
                        )
                    }
                }
                NavHost(
                    navController = navController,
                    startDestination = HarnessDestination.OVERVIEW.route,
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None },
                ) {
                    installHarnessApplicationsGraph(
                        navController = navController,
                        state = applicationsState,
                        mutationState = applicationsMutationState,
                        callbacks = HarnessApplicationsGraphCallbacks(
                            onRefresh = applicationsViewModel::refresh,
                            onRefreshObservedIdentity = {
                                applicationsViewModel.refresh(HarnessApplicationsRefreshMode.OBSERVED_IDENTITY)
                            },
                            onSetDefaultPreset = applicationsViewModel::setDefaultPreset,
                            onSetApplicationConnectionEnabled = applicationsViewModel::setApplicationConnectionEnabled,
                            onCreateApplicationConnection = applicationsViewModel::createApplicationConnection,
                            onCreateCustomPreset = applicationsViewModel::createCustomPreset,
                            onClearMutationFeedback = applicationsViewModel::clearMutationFeedback,
                        ),
                    )
                    composable(HarnessDestination.OVERVIEW.route) {
                        val resource = uiState.resourceHistory.snapshots.firstOrNull()
                        HarnessOverviewScreen(
                            state = uiState,
                            diagnostics = diagnosticsState,
                            processPss = resource?.processPss,
                            thermalStatus = resource?.thermalStatus,
                            onOpenPlayground = { navigate(HarnessDestination.PLAYGROUND) },
                            onOpenModels = { navigate(HarnessDestination.MODELS) },
                            onOpenDiagnostics = { navigate(HarnessDestination.DIAGNOSTICS) },
                        )
                    }
                    composable(HarnessDestination.PLAYGROUND.route) {
                        HarnessPlaygroundScreen(
                            state = uiState,
                            actions = HarnessPlaygroundActions(
                                openModels = { navigate(HarnessDestination.MODELS) },
                                updatePrompt = harnessViewModel::updatePlaygroundPrompt,
                                updatePreset = harnessViewModel::updatePlaygroundPreset,
                                updateThinkingMode = harnessViewModel::updatePlaygroundThinkingMode,
                                updateTemperature = harnessViewModel::updatePlaygroundTemperature,
                                updateTopP = harnessViewModel::updatePlaygroundTopP,
                                updateMaxTokens = harnessViewModel::updatePlaygroundMaxTokens,
                                updateTopK = harnessViewModel::updatePlaygroundTopK,
                                updateMinP = harnessViewModel::updatePlaygroundMinP,
                                updatePresencePenalty = harnessViewModel::updatePlaygroundPresencePenalty,
                                updateRepeatPenalty = harnessViewModel::updatePlaygroundRepeatPenalty,
                                updateRepeatLastN = harnessViewModel::updatePlaygroundRepeatLastN,
                                updateSeed = harnessViewModel::updatePlaygroundSeed,
                                updateContext = harnessViewModel::updatePlaygroundContext,
                                run = ::startPlayground,
                                cancel = { harnessViewModel.cancelPlayground() },
                            ),
                        )
                    }
                    composable(HarnessDestination.PERFORMANCE.route) {
                        PerformanceScreen(
                            state = performanceState,
                            modelOptions = performanceModelOptions(uiState.modelDistribution),
                            profileOptions = emptyList(),
                            runnerAvailable = false,
                            onIntent = performanceViewModel::dispatch,
                            onOpenModels = { navigate(HarnessDestination.MODELS) },
                        )
                    }
                    composable(HarnessDestination.MODELS.route) {
                        ModelsScreen(
                            state = uiState,
                            onOpenModelDetails = { item ->
                                navController.navigate(HarnessRoutes.modelDetail(item))
                            },
                        )
                    }
                    composable(
                        route = HarnessRoutes.MODEL_DETAIL_PATTERN,
                        arguments = listOf(
                            navArgument(HarnessRoutes.MODEL_IDENTITY_ARGUMENT) {
                                type = NavType.StringType
                            },
                        ),
                    ) { entry ->
                        val identity = HarnessRoutes.decodeModelIdentity(
                            entry.arguments?.getString(HarnessRoutes.MODEL_IDENTITY_ARGUMENT),
                        )
                        HarnessModelDetailScreen(
                            presentation = HarnessModelDetails.present(uiState.modelInventory, identity),
                            pendingRecovery = uiState.modelRecoveryConfirmation,
                            busy = uiState.busy,
                            onRequestRecovery = { identity, action ->
                                harnessViewModel.models.recovery.request(identity, action)
                            },
                            onConfirmRecovery = { harnessViewModel.models.recovery.confirm() },
                            onCancelRecovery = harnessViewModel.models.recovery::cancel,
                        )
                    }
                    composable(HarnessDestination.DIAGNOSTICS.route) {
                        DiagnosticsScreen(
                            state = uiState,
                            onOpenRequestTimeline = { requestId ->
                                navController.navigate(HarnessRoutes.requestTimeline(requestId))
                            },
                        )
                    }
                    composable(HarnessDestination.SETTINGS.route) {
                        HarnessSettingsScreen(
                            model = uiState.importedModel,
                            themePreference = uiState.themePreference,
                            onThemeChange = ::updateThemePreference,
                            onOpenPrivacy = {
                                navController.navigate(HarnessSettingsDetail.PRIVACY.route)
                            },
                            onOpenStorage = {
                                navController.navigate(HarnessSettingsDetail.STORAGE.route)
                            },
                            onOpenBuild = {
                                navController.navigate(HarnessSettingsDetail.BUILD.route)
                            },
                            onOpenDeveloperTools = {
                                navController.navigate(HarnessSettingsDetail.DEVELOPER_TOOLS.route)
                            },
                        )
                    }
                    composable(HarnessSettingsDetail.PRIVACY.route) {
                        PrivacyDetailScreen()
                    }
                    composable(HarnessSettingsDetail.STORAGE.route) {
                        StorageDetailScreen(
                            importedModel = uiState.importedModel,
                            onOpenModels = { navigate(HarnessDestination.MODELS) },
                        )
                    }
                    composable(HarnessSettingsDetail.BUILD.route) {
                        BuildDetailScreen(
                            versionName = appVersionName(),
                            versionCode = appVersionCode(),
                            applicationId = packageName,
                        )
                    }
                    composable(HarnessSettingsDetail.DEVELOPER_TOOLS.route) {
                        DeveloperToolsDetailScreen(
                            onOpenHealth = {
                                selectDiagnosticsSection(DiagnosticsSection.HEALTH)
                                navController.navigate(HarnessDestination.DIAGNOSTICS.route) {
                                    launchSingleTop = true
                                }
                            },
                            onOpenLogs = {
                                selectDiagnosticsSection(DiagnosticsSection.LOGS)
                                navController.navigate(HarnessDestination.DIAGNOSTICS.route) {
                                    launchSingleTop = true
                                }
                            },
                            onOpenPhysicalValidation = {
                                navController.navigate(HarnessSettingsDetail.PHYSICAL_VALIDATION.route)
                            },
                        )
                    }
                    composable(HarnessSettingsDetail.PHYSICAL_VALIDATION.route) {
                        PhysicalValidationDetailScreen(
                            modelAvailable = uiState.importedModel != null,
                            busy = isBusy() || diagnosticActionRunning(),
                            latestReport = latestReport,
                            onRunValidation = ::runPhysicalValidation,
                            onCopyReport = ::copyReport,
                            onShareReport = ::shareReport,
                        )
                    }
                    composable(
                        route = HarnessRoutes.REQUEST_TIMELINE_PATTERN,
                        arguments = listOf(
                            navArgument(HarnessRoutes.REQUEST_ID_ARGUMENT) {
                                type = NavType.StringType
                            },
                        ),
                    ) { entry ->
                        val requestId = HarnessRoutes.decodeRequestId(
                            entry.arguments?.getString(HarnessRoutes.REQUEST_ID_ARGUMENT),
                        )
                        LaunchedEffect(requestId) {
                            if (requestId != null) openRequestTimeline(requestId)
                        }
                        DisposableEffect(requestId) {
                            onDispose { closeRequestTimeline() }
                        }
                        RequestTimelineDetailScreen(
                            timeline = selectedRequestTimeline,
                            onCopyLog = ::copyLog,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ModelsScreen(state: HarnessUiState, onOpenModelDetails: (HarnessModelInventoryItem) -> Unit) {
        val inventory = state.modelInventory
        HarnessScreenList(title = null) {
            item { ModelsHeader() }
            if (inventory.degradedCount > 0) {
                item { ModelsRecoveryCard(inventory, onOpenModelDetails) }
            }
            item {
                UnifiedModelsCatalog(
                    state = state,
                    actions = UnifiedModelsActions(
                        catalog = PhoneModelDistributionActions(
                            download = { harnessViewModel.models.executeCatalog(ModelCatalogCommand.Download(it)) },
                            cancelDownload = { harnessViewModel.models.executeCatalog(ModelCatalogCommand.CancelDownload(it)) },
                            install = { harnessViewModel.models.executeCatalog(ModelCatalogCommand.Install(it)) },
                            verifyInstalled = { harnessViewModel.models.executeCatalog(ModelCatalogCommand.VerifyInstalled(it)) },
                            requestRemove = { harnessViewModel.models.executeCatalog(ModelCatalogCommand.RequestRemoval(it)) },
                            cancelRemove = { harnessViewModel.models.executeCatalog(ModelCatalogCommand.CancelRemoval(it)) },
                            confirmRemove = { harnessViewModel.models.executeCatalog(ModelCatalogCommand.ConfirmRemoval(it)) },
                            selectInstalled = { harnessViewModel.models.selectInstalled(it) },
                        ),
                        verifySelected = { harnessViewModel.models.verifySelected() },
                        unloadLoaded = { harnessViewModel.models.unloadLoaded() },
                        requestSelectedRemoval = { harnessViewModel.models.requestSelectedRemoval() },
                        cancelSelectedRemoval = harnessViewModel.models::cancelSelectedRemoval,
                        confirmSelectedRemoval = { harnessViewModel.models.confirmSelectedRemoval() },
                        refresh = { harnessViewModel.models.executeCatalog(ModelCatalogCommand.Refresh) },
                    ),
                    onOpenModelDetails = onOpenModelDetails,
                )
            }
        }
    }

    @Composable
    private fun ModelsHeader() {
        Column {
            Text("Models", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Manage reviewed Qwen3.5 models from the catalog",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @Composable
    private fun ModelsRecoveryCard(inventory: HarnessModelInventoryState, onOpenModelDetails: (HarnessModelInventoryItem) -> Unit) {
        HarnessCard {
            HarnessStatusBadge("RECOVERY REQUIRED", HarnessStatusTone.WARNING)
            Text("Runtime and model selection are not aligned.")
            inventory.items
                .filter { it.lifecycle == HarnessModelLifecycle.DEGRADED }
                .forEach { item ->
                    Text(
                        item.detail ?: item.degradation?.name.orEmpty().replace('_', ' '),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HarnessSecondaryButton("Review ${item.displayName}") {
                        onOpenModelDetails(item)
                    }
                }
            HarnessSecondaryButton("Refresh model state") {
                harnessViewModel.models.executeCatalog(ModelCatalogCommand.Refresh)
            }
        }
    }

    @Composable
    private fun DiagnosticsScreen(state: HarnessUiState, onOpenRequestTimeline: (String) -> Unit) {
        BackHandler(enabled = diagnosticsSection != DiagnosticsSection.OVERVIEW) {
            selectDiagnosticsSection(DiagnosticsSection.OVERVIEW)
        }
        HarnessScreenList(
            title = "Diagnostics",
            supportingText = if (diagnosticsSection == DiagnosticsSection.OVERVIEW) {
                "Source-backed health, performance and privacy-safe evidence"
            } else {
                diagnosticsSection.label
            },
        ) {
            if (diagnosticsSection == DiagnosticsSection.OVERVIEW) {
                item {
                    HarnessDiagnosticsOverview(
                        state = harnessDiagnosticsOverviewState(
                            diagnostics = diagnosticsState,
                            resources = state.resourceHistory,
                            benchmarks = benchmarkState,
                            logs = logState,
                            validationReport = latestReport,
                        ),
                        onOpen = ::selectDiagnosticsSection,
                    )
                }
            } else {
                item {
                    HarnessSecondaryButton(
                        text = "Back to diagnostics",
                        onClick = { selectDiagnosticsSection(DiagnosticsSection.OVERVIEW) },
                    )
                }
                when (diagnosticsSection) {
                    DiagnosticsSection.OVERVIEW -> Unit

                    DiagnosticsSection.HEALTH -> {
                        healthDiagnostics(state)
                        runtimeDiagnostics()
                    }

                    DiagnosticsSection.RUNS -> {
                        runtimeDiagnostics()
                        runDiagnostics(onOpenRequestTimeline)
                    }

                    DiagnosticsSection.RESOURCES -> {
                        runtimeDiagnostics()
                        resourceDiagnostics(state.resourceHistory)
                    }

                    DiagnosticsSection.BENCHMARKS -> {
                        runtimeDiagnostics()
                        benchmarkDiagnostics(state)
                    }

                    DiagnosticsSection.LOGS -> {
                        runtimeDiagnostics()
                        logDiagnostics(
                            state = logState,
                            filter = logFilter,
                            timeline = selectedRequestTimeline,
                            onFilterChange = ::updateLogFilter,
                            onOpenTimeline = onOpenRequestTimeline,
                            onCloseTimeline = ::closeRequestTimeline,
                            onCopyLog = ::copyLog,
                        )
                    }

                    DiagnosticsSection.VALIDATION -> {
                        runtimeDiagnostics()
                        validationDiagnostics(state)
                    }
                }
            }
        }
    }

    private fun androidx.compose.foundation.lazy.LazyListScope.runtimeDiagnostics() {
        item {
            HarnessCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Runtime", style = MaterialTheme.typography.titleLarge)
                        Text(operationStatus, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HarnessStatusBadge(
                        diagnosticsState.runtime?.state?.name ?: "UNAVAILABLE",
                        if (diagnosticsState.runtime == null) HarnessStatusTone.NEUTRAL else HarnessStatusTone.SUCCESS,
                    )
                }
                HarnessSecondaryButton("Refresh diagnostics", onClick = ::refreshDiagnostics)
            }
        }
    }

    private fun androidx.compose.foundation.lazy.LazyListScope.healthDiagnostics(state: HarnessUiState) {
        item {
            HarnessCard(emphasized = true) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    HarnessRuntimeGlyph(
                        ready = diagnosticsState.healthStatus.equals("Pass", ignoreCase = true),
                        modifier = Modifier.size(52.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Overall health", style = MaterialTheme.typography.labelLarge)
                        Text(
                            diagnosticsState.healthStatus,
                            style = MaterialTheme.typography.headlineMedium,
                            color = if (diagnosticsState.healthStatus.equals("Pass", ignoreCase = true)) {
                                HarnessColors.Secondary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Text(
                            if (diagnosticsState.health.isEmpty()) {
                                "Run checks to validate the local runtime"
                            } else {
                                "Available checks have completed; review any warning or failure below."
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                if (diagnosticsState.health.isEmpty()) {
                    listOf(
                        "Runtime" to "Not run",
                        "Model integrity" to if (state.importedModel == null) "No model" else "Not run",
                        "Generation sanity" to "Not run",
                        "Cache" to "Not run",
                    ).forEachIndexed { index, (label, status) ->
                        HealthCheckPreviewRow(label, status, index < 3)
                    }
                }
                HarnessPrimaryButton(
                    if (diagnosticsState.health.isEmpty()) "Run all checks" else "Run checks again",
                    enabled = !isBusy() && !diagnosticActionRunning(),
                    onClick = ::runHealthChecks,
                )
            }
        }
        items(diagnosticsState.health, key = { it.id }) { health ->
            HarnessCard {
                Text(health.id, style = MaterialTheme.typography.titleMedium)
                Text(health.detail)
                HarnessMetricRow {
                    HarnessMetric("Status", health.status, Modifier.weight(1f))
                    HarnessMetric("Duration", health.duration, Modifier.weight(1f))
                }
            }
        }
    }

    @Composable
    private fun HealthCheckPreviewRow(label: String, status: String, showDivider: Boolean) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HarnessDestinationIcon(HarnessDestination.DIAGNOSTICS, selected = false, modifier = Modifier.size(14.dp))
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (showDivider) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
    }

    private fun androidx.compose.foundation.lazy.LazyListScope.resourceDiagnostics(history: DiagnosticsResourceHistoryUi) {
        item {
            HarnessCard {
                Text("Device resources", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (history.snapshots.isEmpty()) {
                        "No resource snapshots have been captured in this process."
                    } else {
                        "${history.sampleCount} bounded snapshots retained. " +
                            "Unsupported values remain unavailable."
                    },
                )
                HarnessPrimaryButton(
                    "Capture resource snapshot",
                    enabled = !isBusy() && !diagnosticActionRunning(),
                    onClick = ::captureResourceSnapshot,
                )
            }
        }
        if (history.snapshots.isNotEmpty()) {
            item {
                HarnessCard {
                    Text("Resource history summary", style = MaterialTheme.typography.titleMedium)
                    HarnessMetricRow {
                        HarnessMetric("Current PSS", history.currentProcessPss, Modifier.weight(1f))
                        HarnessMetric("Trend", history.processPssTrend, Modifier.weight(1f))
                    }
                    HarnessMetricRow {
                        HarnessMetric("Minimum PSS", history.minimumProcessPss, Modifier.weight(1f))
                        HarnessMetric("Maximum PSS", history.maximumProcessPss, Modifier.weight(1f))
                    }
                    HarnessMetricRow {
                        HarnessMetric(
                            "Low-memory samples",
                            history.lowMemorySamples.toString(),
                            Modifier.weight(1f),
                        )
                        HarnessMetric("Thermal states", history.observedThermalStates, Modifier.weight(1f))
                    }
                }
            }
        }
        items(history.snapshots.take(RESOURCE_HISTORY_VISIBLE_LIMIT), key = { it.timestampEpochMs }) { resource ->
            HarnessCard {
                Text(
                    java.time.Instant.ofEpochMilli(resource.timestampEpochMs).toString(),
                    style = MaterialTheme.typography.titleMedium,
                )
                HarnessMetricRow {
                    HarnessMetric("Process PSS", resource.processPss, Modifier.weight(1f))
                    HarnessMetric("Native heap", resource.nativeHeap, Modifier.weight(1f))
                }
                HarnessMetricRow {
                    HarnessMetric("Java heap", resource.javaHeap, Modifier.weight(1f))
                    HarnessMetric("Available", resource.availableMemory, Modifier.weight(1f))
                }
                HarnessMetricRow {
                    HarnessMetric("Low memory", resource.lowMemory, Modifier.weight(1f))
                    HarnessMetric("Thermal", resource.thermalStatus, Modifier.weight(1f))
                }
            }
        }
    }

    private fun androidx.compose.foundation.lazy.LazyListScope.benchmarkDiagnostics(state: HarnessUiState) {
        item {
            HarnessCard {
                Text("Benchmarks", style = MaterialTheme.typography.titleLarge)
                HarnessMetricRow {
                    HarnessMetric("Baselines", benchmarkState.baselines.size.toString(), Modifier.weight(1f))
                    HarnessMetric("Known keys", benchmarkState.readiness.size.toString(), Modifier.weight(1f))
                    HarnessMetric("History", benchmarkState.history.size.toString(), Modifier.weight(1f))
                }
                Text(
                    benchmarkState.sourceError
                        ?: benchmarkState.captureDetail
                        ?: "Readiness is evaluated independently for every app, use case, model and cold/warm key.",
                )
                HarnessPrimaryButton(
                    "Capture all ready baselines",
                    enabled = state.importedModel != null &&
                        benchmarkState.readiness.any { it.captureReady } &&
                        !isBusy() &&
                        !diagnosticActionRunning(),
                    onClick = ::captureBenchmarkBaselines,
                )
            }
        }
        items(benchmarkState.readiness, key = { "readiness:${it.stableId}" }) { readiness ->
            HarnessCard {
                Text(
                    "${readiness.useCase} · ${readiness.loadKind}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(readiness.detail)
                HarnessMetricRow {
                    HarnessMetric(
                        "Baseline samples",
                        "${readiness.baselineSamples}/${readiness.baselineRequired}",
                        Modifier.weight(1f),
                    )
                    HarnessMetric(
                        "Post-baseline",
                        "${readiness.comparisonSamples}/${readiness.comparisonRequired}",
                        Modifier.weight(1f),
                    )
                }
                HarnessMetricRow {
                    HarnessMetric(
                        "Baseline",
                        if (readiness.baselineCaptured) "Captured" else "Not captured",
                        Modifier.weight(1f),
                    )
                    HarnessMetric(
                        "Regression",
                        if (readiness.comparisonReady) "Ready" else "Not ready",
                        Modifier.weight(1f),
                    )
                }
                if (!readiness.baselineCaptured) {
                    HarnessSecondaryButton(
                        "Capture this baseline",
                        enabled = readiness.captureReady && !isBusy() && !diagnosticActionRunning(),
                    ) {
                        captureBenchmarkBaseline(readiness.stableId)
                    }
                }
            }
        }
        if (benchmarkState.history.isNotEmpty()) {
            item {
                Text("Retained baseline history", style = MaterialTheme.typography.titleLarge)
            }
        }
        items(benchmarkState.history, key = { "history:${it.stableId}" }) { history ->
            HarnessCard {
                Text(
                    "${history.useCase} · ${history.loadKind}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(history.capturedAt)
                HarnessMetricRow {
                    HarnessMetric("State", if (history.active) "Active" else "Historical", Modifier.weight(1f))
                    HarnessMetric("Samples", history.samples, Modifier.weight(1f))
                }
                HarnessMetricRow {
                    HarnessMetric("Median TTFT", history.medianTtft, Modifier.weight(1f))
                    HarnessMetric("p95 total", history.p95Total, Modifier.weight(1f))
                }
                HarnessMetric("Median decode", history.medianDecode)
            }
        }

        items(benchmarkState.baselines, key = { "baseline:${it.stableId}" }) { benchmark ->
            HarnessCard {
                Text(
                    "${benchmark.useCase} · ${benchmark.loadKind}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(benchmark.regressionDetail)
                HarnessMetricRow {
                    HarnessMetric("Samples", benchmark.samples, Modifier.weight(1f))
                    HarnessMetric("Regression", benchmark.regressionStatus, Modifier.weight(1f))
                }
                HarnessMetricRow {
                    HarnessMetric("Median TTFT", benchmark.medianTtft, Modifier.weight(1f))
                    HarnessMetric("p95 total", benchmark.p95Total, Modifier.weight(1f))
                }
                HarnessMetric("Median decode", benchmark.medianDecode)
            }
        }
    }

    private fun androidx.compose.foundation.lazy.LazyListScope.runDiagnostics(onOpenRequestTimeline: (String) -> Unit) {
        item {
            HarnessCard {
                Text("Generation runs", style = MaterialTheme.typography.titleLarge)
                when {
                    diagnosticsState.sourceError != null -> Text(requireNotNull(diagnosticsState.sourceError))

                    diagnosticsState.runs.isEmpty() -> {
                        Text("No telemetry runs yet. Run a local prompt or validation to populate this view.")
                    }

                    else -> {
                        Text("${diagnosticsState.runs.size} privacy-safe run records retained in this process.")
                    }
                }
            }
        }
        items(diagnosticsState.runs, key = { it.requestId }) { run ->
            HarnessCard {
                Text(run.status, style = MaterialTheme.typography.titleMedium)
                Text(run.useCase, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HarnessMetricRow {
                    HarnessMetric("Load", run.modelLoadKind, Modifier.weight(1f))
                    HarnessMetric("TTFT", run.timeToFirstToken, Modifier.weight(1f))
                }
                HarnessMetricRow {
                    HarnessMetric("Total", run.totalDuration, Modifier.weight(1f))
                    HarnessMetric("Decode", run.throughput, Modifier.weight(1f))
                }
                HarnessMetric("Model", run.modelDigestPrefix + "…")
                HarnessMetric("Request", run.requestId.take(12) + "…")
                HarnessSecondaryButton("View request timeline") {
                    onOpenRequestTimeline(run.requestId)
                }
            }
        }
    }

    private fun runPhysicalValidation() {
        afterPlaygroundRuntimeReleased {
            latestReport = ""
            operationStatus = "Starting validation…"
            controller.runFullValidation()
        }
    }

    private fun androidx.compose.foundation.lazy.LazyListScope.validationDiagnostics(state: HarnessUiState) {
        item {
            HarnessCard {
                Text("Physical-device validation", style = MaterialTheme.typography.titleLarge)
                Text("Runs generation, cancellation and repeated load/generate/unload memory cycles.")
                HarnessPrimaryButton(
                    "Run full validation",
                    enabled = state.importedModel != null && !isBusy() && !diagnosticActionRunning(),
                    onClick = ::runPhysicalValidation,
                )
            }
        }
        item {
            HarnessCard {
                Text("Privacy-safe report", style = MaterialTheme.typography.titleMedium)
                SelectionContainer {
                    Text(
                        latestReport.ifBlank { "No validation report yet." },
                        fontFamily = FontFamily.Monospace,
                    )
                }
                HarnessSecondaryButton(
                    "Copy report",
                    enabled = latestReport.isNotBlank(),
                    onClick = ::copyReport,
                )
                HarnessSecondaryButton(
                    "Share report",
                    enabled = latestReport.isNotBlank(),
                    onClick = ::shareReport,
                )
            }
        }
    }

    private fun createModelEffects(): ModelEffects = object : ModelEffects {
        override fun snapshot(): ModelEffectsSnapshot = ModelEffectsSnapshot(
            distribution = modelDistributionController.snapshot(),
            selectedModel = controller.snapshotModel(),
            loadedDigest = runtimeGraph.loadedModelDigest?.sha256,
        )

        override fun executeCatalog(command: ModelCatalogCommand): Boolean = modelEffect {
            when (command) {
                ModelCatalogCommand.Refresh -> modelDistributionController.refresh()
                is ModelCatalogCommand.Download -> modelDistributionController.download(command.stableId)
                is ModelCatalogCommand.CancelDownload -> modelDistributionController.cancelDownload(command.stableId)
                is ModelCatalogCommand.Install -> modelDistributionController.install(command.stableId)
                is ModelCatalogCommand.VerifyInstalled -> modelDistributionController.verifyInstalled(command.stableId)
                is ModelCatalogCommand.RequestRemoval -> modelDistributionController.requestRemove(command.stableId)
                is ModelCatalogCommand.CancelRemoval -> modelDistributionController.cancelRemove(command.stableId)
                is ModelCatalogCommand.ConfirmRemoval -> modelDistributionController.confirmRemove(command.stableId)
            }
        }

        override fun selectInstalled(metadata: InstalledCatalogModelMetadata): Boolean = afterPlaygroundRuntimeReleased {
            controller.selectInstalledModel(metadata.asImportedPhoneModel())
        }

        override fun verifySelected(): Boolean = verifySelectedModel()

        override fun removeSelected(): Boolean = afterPlaygroundRuntimeReleased(controller::removeModel)

        override fun executeRecovery(command: ModelRecoveryCommand): Boolean = when (command) {
            is ModelRecoveryCommand.AdoptLoadedSelection -> {
                if (runtimeGraph.loadedModelDigest?.sha256 != command.metadata.digest.sha256) {
                    false
                } else {
                    modelEffect {
                        controller.selectInstalledModel(command.metadata.asImportedPhoneModel())
                    }
                }
            }

            ModelRecoveryCommand.ReleaseRuntime -> afterPlaygroundRuntimeReleased(::syncLoadedModelOwnership)
        }
    }

    private fun modelEffect(action: () -> Unit): Boolean = runCatching {
        action()
        true
    }.getOrDefault(false)

    private fun syncLoadedModelOwnership() {
        harnessViewModel.dispatch(
            HarnessUiEvent.LoadedModelChanged(runtimeGraph.loadedModelDigest?.sha256),
        )
    }

    private fun startPlayground() {
        when (harnessViewModel.startPlayground()) {
            PlaygroundStartResult.STARTED -> Unit

            PlaygroundStartResult.MODEL_REQUIRED -> {
                Toast.makeText(this, "Select a local model first", Toast.LENGTH_SHORT).show()
            }

            PlaygroundStartResult.BUSY -> {
                Toast.makeText(this, "Wait for the active operation to finish", Toast.LENGTH_SHORT).show()
            }

            PlaygroundStartResult.INVALID_SETTINGS -> {
                Toast.makeText(this, "Review the invalid generation setting shown in Playground", Toast.LENGTH_LONG).show()
            }

            PlaygroundStartResult.CONTROLLER_UNAVAILABLE,
            PlaygroundStartResult.REJECTED,
            -> Toast.makeText(this, "Unable to start local inference", Toast.LENGTH_LONG).show()
        }
    }

    private fun afterPlaygroundRuntimeReleased(action: () -> Unit): Boolean {
        val released = harnessViewModel.releasePlaygroundRuntime {
            runOnUiThread {
                syncLoadedModelOwnership()
                action()
            }
        }
        if (!released) {
            Toast.makeText(this, "Cancel or wait for the active generation", Toast.LENGTH_SHORT).show()
        }
        return released
    }

    private fun updateKeepScreenOn() {
        if (harnessViewModel.uiState.value.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun diagnosticActionRunning(): Boolean = harnessViewModel.uiState.value.diagnosticActionRunning

    private fun isBusy(): Boolean = harnessViewModel.uiState.value.busy

    private fun copyLog(log: DiagnosticsLogUi) {
        copyToClipboard("Harness log entry", log.copyText())
        Toast.makeText(this, "Log entry copied", Toast.LENGTH_SHORT).show()
    }

    private fun copyReport() {
        copyToClipboard("Harness validation", latestReport)
        Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show()
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun shareReport() {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Harness physical-device validation")
                    putExtra(Intent.EXTRA_TEXT, latestReport)
                },
                "Share validation report",
            ),
        )
    }

    private fun appVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("0.0.0")

    private fun appVersionCode(): String = runCatching {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toString()
        }
    }.getOrDefault("0")

    private companion object {
        const val STATE_REPORT = "report"
        const val RESOURCE_HISTORY_VISIBLE_LIMIT = 10
    }
}
