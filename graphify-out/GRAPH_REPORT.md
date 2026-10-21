# Graph Report - TinyModels  (2026-10-21)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 2125 nodes · 5785 edges · 104 communities (49 shown, 55 thin omitted)
- Extraction: 99% EXTRACTED · 1% INFERRED · 0% AMBIGUOUS · INFERRED: 85 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `f541ca6b`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- ChatInputBar.kt
- BenchmarkMathTest
- DeviceViewModelTest.kt
- RamUsageChart.kt
- WebSearchClient.kt
- FakeHomeChatRepository
- BenchmarkScreen.kt
- TinyModelsApp
- NetworkMonitor
- DownloadedFileDetailViewModelTest
- ChatDao
- ChatViewModel
- ChatViewModel.kt
- ModelDownloadWorker.kt
- ModelManager
- SettingsScreen.kt
- AppResult
- ChatUiState
- FakeListModelRepository
- DownloadNotifier.kt
- Test
- ModelChip.kt
- DownloadedModelFile
- WebSearchClient
- SettingsRepositoryImpl
- MainActivity.kt
- DownloadedModel
- WebSearchParser
- ConversationSession.kt
- ModelDetails
- SettingsRepository
- DeviceInsights
- AppSettings
- BenchmarkViewModel.kt
- .formatBytes
- .rebuildSession
- WebSearchResult
- WebSearchPrompt
- MessageBubble.kt
- Composable
- DownloadManager
- ChatRepositoryImpl
- ChatEvent
- ModelListViewModel.kt
- ModelFileDao
- StateViews.kt
- FontChoice
- InferenceSettingsSheet.kt
- HomeViewModel.kt
- ModelRepositoryImplExportTest.kt
- FakeFileDetailRepository
- DatabaseModule.kt
- WebSearchResults
- .extractReadableText
- ChatRepository
- FakeDownloadedModelsRepository
- DownloadedModelDao
- DownloadModelUseCase
- ModelDetailsViewModel
- SettingsViewModel
- FakeImportModelRepository
- AiCapabilityClassifierTest
- DeviceInfoState
- ModelRepositoryImplImportTest.kt
- HomeViewModelTest
- buildPlusMenuItems
- DownloadedModelsViewModelTest
- DispatcherProvider
- TinyModelsDatabase
- RepositoryModule.kt
- DownloadStatus
- asstateflow
- ModelVisionSupportTest
- WebSearchClientTest
- InferenceError
- Composable
- computeRecommendations
- DownloadedFileDetailViewModel.kt
- tinymodels/TinyModelsApp.kt
- ChatSummary
- FileDownloadStatus
- DownloadState
- toPoints
- .pickForDevice
- VisionMessageTest.kt
- ChatRoomScreen
- StorageUtils
- file
- make_posters.py
- ExampleInstrumentedTest.kt
- ChatMessage
- ChatScreenContent
- AiCapability
- Role
- ExampleUnitTest.kt

## God Nodes (most connected - your core abstractions)
1. `DownloadedModelFile` - 67 edges
2. `AppResult` - 60 edges
3. `ChatViewModel` - 58 edges
4. `DownloadedModel` - 54 edges
5. `ModelRepository` - 50 edges
6. `SettingsRepository` - 45 edges
7. `ModelDetails` - 43 edges
8. `ChatRepository` - 39 edges
9. `ModelManager` - 33 edges
10. `ChatDao` - 32 edges

## Surprising Connections (you probably didn't know these)
- `Error` --references--> `InferenceError`  [EXTRACTED]
  app/src/main/java/com/ai/tinymodels/core/inference/ModelManager.kt → app/src/main/java/com/ai/tinymodels/core/common/AppResult.kt
- `UiState` --references--> `DownloadedModelFile`  [EXTRACTED]
  app/src/main/java/com/ai/tinymodels/feature/models/DownloadedFileDetailViewModel.kt → app/src/main/java/com/ai/tinymodels/domain/model/Model.kt
- `WebSearchClientTest` --calls--> `WebSearchClient`  [INFERRED]
  app/src/test/java/com/ai/tinymodels/core/network/WebSearchClientTest.kt → app/src/main/java/com/ai/tinymodels/core/network/WebSearchClient.kt
- `MessageBubble()` --calls--> `AttachedPhoto()`  [INFERRED]
  app/src/main/java/com/ai/tinymodels/feature/chat/components/MessageBubble.kt → app/src/main/java/com/ai/tinymodels/feature/chat/components/ChatImage.kt
- `MessageBubble()` --calls--> `StreamingCursor()`  [INFERRED]
  app/src/main/java/com/ai/tinymodels/feature/chat/components/MessageBubble.kt → app/src/main/java/com/ai/tinymodels/feature/chat/components/ChatInputBar.kt

## Import Cycles
- None detected.

## Communities (104 total, 55 thin omitted)

### Community 1 - "ChatInputBar.kt"
Cohesion: 0.05
Nodes (7): AttachedPhoto(), ChatImageFiles, ChatInputBar(), MenuIconContainer(), MenuSectionLabel(), PhotoPreviewRow(), StreamingCursor()

### Community 2 - "BenchmarkMathTest"
Cohesion: 0.07
Nodes (26): BenchmarkMath, BenchmarkResult, PromptResult, PromptSpec, RunMetrics, BenchmarkScreen(), BulletPoint(), clickableNoRipple() (+18 more)

### Community 3 - "DeviceViewModelTest.kt"
Cohesion: 0.06
Nodes (6): MemorySnapshot, MemoryUtils, CancelDownloadReceiver, DeviceViewModel, MemoryUtilsTest, DeviceViewModelTest

### Community 4 - "RamUsageChart.kt"
Cohesion: 0.06
Nodes (7): Formatters, ModelLoadingDialog(), RamRecommendations(), buildPath(), RamUsageChart(), UsageLabels, usageStats()

### Community 5 - "WebSearchClient.kt"
Cohesion: 0.08
Nodes (5): ModelDtoParser, HttpException, NetworkErrorMapper, NetworkErrorMapperTest, ModelDtoParserTest

### Community 7 - "BenchmarkScreen.kt"
Cohesion: 0.10
Nodes (4): ChatSessionRow(), ChatTabScreen(), formatRelativeTime(), EditMessageDialog()

### Community 8 - "TinyModelsApp"
Cohesion: 0.07
Nodes (13): DownloadedFileDetailScreen(), FileDetailContent(), InfoRow(), DownloadedFileRow(), DownloadedModelsScreen(), formatBytes(), ImportedTag(), Routes (+5 more)

### Community 9 - "NetworkMonitor"
Cohesion: 0.10
Nodes (6): ConnectivityMonitor, NetworkMonitor, NetworkModule, FakeNetworkMonitor, ModelDetailsViewModelTest, FakeNetworkMonitor

### Community 12 - "ChatDao"
Cohesion: 0.11
Nodes (3): ChatDao, ChatSummaryRow, SessionTokenStatsRow

### Community 15 - "ModelDownloadWorker.kt"
Cohesion: 0.09
Nodes (3): HuggingFaceApi, HuggingFaceAuth, ModelDownloadWorker

### Community 16 - "ModelManager"
Cohesion: 0.12
Nodes (11): EngineState, Error, Idle, Loading, ModelLoadStage, INITIALIZING, LOADING_WEIGHTS, READY (+3 more)

### Community 17 - "SettingsScreen.kt"
Cohesion: 0.17
Nodes (19): AboutSection(), AppearanceSection(), ChatSection(), DownloadsSection(), FontBottomSheet(), FontRow(), HuggingFaceSection(), InferenceSection() (+11 more)

### Community 18 - "AppResult"
Cohesion: 0.10
Nodes (13): AppError, AppResult, Auth, Error, Network, NoConnection, NotFound, ServerError (+5 more)

### Community 19 - "ChatUiState"
Cohesion: 0.11
Nodes (21): ChatHistoryDrawer(), ChatHistoryRow(), formatRelative(), ChatListItem, ChatUiState, ContextUsage, Error, GenerationState (+13 more)

### Community 23 - "ModelChip.kt"
Cohesion: 0.13
Nodes (7): bottomSheetSafePadding(), bottomSheetTopSafePadding(), ModelChip(), ModelPickerRow(), ModelPickerSheet(), ModelFilePickerSheet(), ModelFileRow()

### Community 24 - "DownloadedModelFile"
Cohesion: 0.11
Nodes (3): DownloadedModelFile, ModelRepository, FakeHomeModelRepository

### Community 25 - "WebSearchClient"
Cohesion: 0.17
Nodes (4): WebSearchClient, SearchEndpoint, WebSearchEndpoints, WebSearchClientOrchestrationTest

### Community 27 - "MainActivity.kt"
Cohesion: 0.12
Nodes (3): resolveColorScheme(), TinyModelsTheme(), MainActivity

### Community 32 - "SettingsRepository"
Cohesion: 0.09
Nodes (6): BackendPreference, AUTO, CPU, GPU, EngineLoadConfig, SettingsRepository

### Community 33 - "DeviceInsights"
Cohesion: 0.13
Nodes (12): batteryLevelClamped(), batteryStatusLabel(), DeviceInsights(), InfoLine(), InsightChip(), SectionTitle(), StorageBar(), thermalLabel() (+4 more)

### Community 34 - "AppSettings"
Cohesion: 0.10
Nodes (6): AppSettings, ThemeMode, DARK, LIGHT, SYSTEM, FakeSettingsRepository

### Community 35 - "BenchmarkViewModel.kt"
Cohesion: 0.15
Nodes (4): BenchmarkRunner, PssMonitor, SamplerSettings, BenchmarkViewModel

### Community 36 - ".formatBytes"
Cohesion: 0.19
Nodes (16): AboutCard(), Chip(), DownloadBar(), FileDownloadBar(), FileDownloadIdle(), FileDownloadInProgress(), FileRow(), HeaderCard() (+8 more)

### Community 37 - ".rebuildSession"
Cohesion: 0.19
Nodes (5): ChatTurn, Role, ASSISTANT, USER, ModelVisionSupport

### Community 38 - "WebSearchResult"
Cohesion: 0.19
Nodes (4): Scored, WebSearchFilter, WebSearchResult, WebSearchFilterTest

### Community 40 - "MessageBubble.kt"
Cohesion: 0.16
Nodes (7): buildTimestampLabel(), canRenderMarkdown(), formatDuration(), formatTime(), MessageBubble(), MessageFooter(), SafeMarkdown()

### Community 41 - "Composable"
Cohesion: 0.20
Nodes (15): CapabilityVerdictCard(), DashboardHome(), FirstTimeHome(), HomeScreen(), InfoRow(), LastUsedModelCard(), ManageModelsRow(), ModelRow() (+7 more)

### Community 42 - "DownloadManager"
Cohesion: 0.12
Nodes (3): DownloadManager, DownloadRequest, DownloadManagerService

### Community 44 - "ChatEvent"
Cohesion: 0.11
Nodes (16): InferenceSettings, CancelGeneration, ChatEvent, CloseInferenceSettings, CompactConversation, DeleteChat, DismissError, EditMessage (+8 more)

### Community 45 - "ModelListViewModel.kt"
Cohesion: 0.17
Nodes (6): ModelFilter, ALL, IMAGE_GENERATION, TEXT_GENERATION, ModelListViewModel, UiState

### Community 47 - "StateViews.kt"
Cohesion: 0.24
Nodes (7): EmptyState(), ErrorState(), OfflineState(), StateView(), ModelListItem(), ModelListScreen(), BrowseContent()

### Community 48 - "FontChoice"
Cohesion: 0.14
Nodes (9): buildTypography(), fontFamilyFor(), FontChoice, ABEL, ABRIL_FATFACE, INTER, PACIFICO, ROBOTO (+1 more)

### Community 49 - "InferenceSettingsSheet.kt"
Cohesion: 0.15
Nodes (3): BackendDropdown(), InferenceSettingsSheet(), SliderSetting()

### Community 50 - "HomeViewModel.kt"
Cohesion: 0.17
Nodes (4): HomeViewModel, FirstTime, RecommendedModel, RecommendedModels

### Community 54 - "WebSearchResults"
Cohesion: 0.19
Nodes (5): WebSearchToolSet, WebSearchRepositoryImpl, WebSearchResults, WebSearchException, WebSearchRepository

### Community 64 - "DeviceInfoState"
Cohesion: 0.26
Nodes (7): DeviceInfoState, Dashboard, DownloadedModelInfo, LastChatSummary, StorageBreakdown, UsageStats, DeviceInfoStateTest

### Community 67 - "buildPlusMenuItems"
Cohesion: 0.30
Nodes (5): AttachPhoto, buildPlusMenuItems(), PlusMenuItem, WebSearch, ChatOptionsMenuTest

### Community 69 - "DispatcherProvider"
Cohesion: 0.31
Nodes (3): DefaultDispatcherProvider, DispatcherProvider, AppModule

### Community 70 - "TinyModelsDatabase"
Cohesion: 0.27
Nodes (3): ChatEntity, MessageEntity, TinyModelsDatabase

### Community 72 - "DownloadStatus"
Cohesion: 0.19
Nodes (11): ActiveDownload, DownloadStatus, CHECKING_SIZE, COMPLETED, DOWNLOADING, FAILED, IDLE, WAITING_FOR_NETWORK (+3 more)

### Community 73 - "asstateflow"
Cohesion: 0.26
Nodes (6): SnackbarDuration, LONG, SHORT, SnackbarManager, SnackbarMessage, toMillis()

### Community 76 - "InferenceError"
Cohesion: 0.18
Nodes (8): BackendUnavailable, GenerationFailed, InferenceError, LoadFailed, ModelFileMissing, NoModelLoaded, OutOfMemory, InferenceException

### Community 77 - "Composable"
Cohesion: 0.27
Nodes (7): DownloadedContent(), DownloadedFileCard(), ModelCard(), ModelsTabScreen(), PipelineChip(), SmallChip(), StatBadge()

### Community 83 - "FileDownloadStatus"
Cohesion: 0.20
Nodes (5): FileDownloadStatus, DOWNLOADED, DOWNLOADING, FAILED, NOT_DOWNLOADED

### Community 88 - "ChatRoomScreen"
Cohesion: 0.42
Nodes (7): ChatRoomEmpty(), ChatRoomScreen(), CompactionChip(), ContextMetricRow(), ContextMetricsSheet(), ContextUsageIcon(), MessageListRoom()

### Community 91 - "make_posters.py"
Cohesion: 0.36
Nodes (4): bubble(), feature_graphic(), font(), gradient()

### Community 94 - "ChatScreenContent"
Cohesion: 0.43
Nodes (4): ChatScreen(), ChatScreenContent(), EmptyState(), MessageList()

### Community 95 - "AiCapability"
Cohesion: 0.33
Nodes (6): AiCapability, EXCELLENT, GOOD, LIMITED, LOW, UNKNOWN

### Community 96 - "Role"
Cohesion: 0.50
Nodes (4): Role, ASSISTANT, SYSTEM, USER

## Knowledge Gaps
- **76 isolated node(s):** `Idle`, `Auth`, `Network`, `NoConnection`, `NotFound` (+71 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 445 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **55 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `SettingsRepository` connect `SettingsRepository` to `AppSettings`, `BenchmarkViewModel.kt`, `FakeHomeChatRepository`, `RepositoryModule.kt`, `MainActivity.kt`, `ModelDetailsViewModelTest.kt`, `ChatViewModel`, `DownloadModelUseCase.kt`, `ChatViewModel.kt`, `DownloadedFileDetailViewModel.kt`, `tinymodels/TinyModelsApp.kt`, `HomeViewModel.kt`, `SettingsRepositoryImpl`, `DownloadModelUseCase`, `SettingsViewModel`?**
  _High betweenness centrality (0.075) - this node is a cross-community bridge._
- **What connects `Idle`, `Auth`, `Network` to the rest of the system?**
  _76 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `ModelDetailsScreen.kt` be split into smaller, more focused modules?**
  _Cohesion score 0.096579476861167 - nodes in this community are weakly interconnected._
- **Why does `DownloadedModelFile` connect `DownloadedModelFile` to `ModelDetailsScreen.kt`, `TinyModelsApp`, `DownloadedFileDetailViewModelTest`, `ModelDetailsViewModelTest.kt`, `ChatViewModel`, `ChatViewModel.kt`, `ModelManager`, `AppResult`, `ModelChip.kt`, `DownloadedModel`, `ModelDetails`, `FakeFileDetailRepository`, `FakeDownloadedModelsRepository`, `ModelDetailsViewModel`, `FakeImportModelRepository`, `HomeViewModelTest`, `DownloadedModelsViewModelTest`, `Composable`, `DownloadedFileDetailViewModel.kt`, `FileDownloadStatus`, `DownloadState`?**
  _High betweenness centrality (0.072) - this node is a cross-community bridge._
- **Should `ChatInputBar.kt` be split into smaller, more focused modules?**
  _Cohesion score 0.04590163934426229 - nodes in this community are weakly interconnected._
- **Why does `ModelRepository` connect `DownloadedModelFile` to `BenchmarkViewModel.kt`, `RepositoryModule.kt`, `ModelDetailsViewModelTest.kt`, `ChatViewModel`, `ChatViewModel.kt`, `ModelDetailsViewModel`, `DownloadedFileDetailViewModel.kt`, `ModelListViewModel.kt`, `AppResult`, `FileDownloadStatus`, `HomeViewModel.kt`, `FakeFileDetailRepository`, `FakeListModelRepository`, `FakeDownloadedModelsRepository`, `DownloadedModel`, `SettingsViewModel`, `FakeImportModelRepository`, `ModelDetails`?**
  _High betweenness centrality (0.069) - this node is a cross-community bridge._
- **Should `BenchmarkMathTest` be split into smaller, more focused modules?**
  _Cohesion score 0.07049180327868852 - nodes in this community are weakly interconnected._