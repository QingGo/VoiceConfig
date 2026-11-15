# Changelog

## v0.1.3 - Patch Release

### Fixes
- Default chat model is now deepseek-flash with legacy migration.
- Permission engine distinguishes auto, commit, strong confirmation and blocked actions.
- Background tasks now enter WAITING_HUMAN instead of failing or auto-executing.
- remote_node is opt-in with 120s timeout and structured results.
- Release CI now builds a consistently signed APK.

### Added
- Tool visibility policy, remote-node opt-in and model tool list.
- Dynamic AgentTool JSON Schema and unified ToolResult envelope.
- HTTP Tool Pack importer/executor and MCP-over-HTTP provider.
- External tools settings with Keystore-encrypted tokens and TLS pin.
- DeepSeek ASR/TTS providers and WebSocket streaming TTS.
- Speech cloud consent and system fallback settings.

### Tests
- App unit tests: 179 passed.
- core:nlp, core:scheduler and core:executor tests passed.

### Environment note
- In the current sandbox, Gradle could not launch the app test executor; the app suite was executed directly with JUnitCore. Run ./gradlew :app:testDebugUnitTest on a normal development machine.
