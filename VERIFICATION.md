# Verification — 2026-10-10

## Passed

- Gradle 8.7 build with JDK 11, Java 11 bytecode.
- The same twelve behavioral tests passed on the installed game Java runtime as well as JDK 11.
- Twelve dependency-free behavioral tests: defaults/identity; destination and config validation; UTF-8 configuration persistence; JSON encoding/metrics; CPU normalization/backoff; optional-client list separation and unsafe-list rejection; HTTP contract and tick freshness; HTTP errors/redirect rejection; timeout; scheduler lifecycle/nonblocking ticks; client guard and disabled server mode.
- Real ServerPanel API integration: existing Release assembly, isolated in-memory HostRegistry and ephemeral loopback listener. A Java heartbeat using the installed game's Java runtime was accepted with HTTP 200. Host identity, category, Healthy state, CPU range, heap value and bridge version were checked.
- No tests connected to the user's active panel database or started/stopped a game server.

The HTTP client explicitly uses HTTP/1.1; default cleartext HTTP/2 upgrade caused local Java 11 HttpServer test timeouts.

- Sixteen installer assertions passed in isolated temporary directories: embedded JAR, INI/profile/BOM preservation, existing config, repeat/read-only updates, backups, server/client modes, invalid selection rejection, unknown directory protection, locked-settings rollback, late locked-JAR rollback of changed INI/profile and JAR integrity.
- Live client join without ServerPanelBridge remains unverified; locally inspected ConnectionDetails.writeMods uses GameServer.ServerMods, separate from the loaded runtime list.

- Shipped EXE CLI smoke passed: client installation into a path containing spaces, embedded JAR present, and invalid server selection returns exit code 1.

## Not yet verified

- Live dedicated-server startup and ZombieBuddy acceptance of this unsigned JAR.
- Native ServerPanel UI observation with a running PZ server.
- Steam Workshop distribution. No item or signing identity has been created.
- Real dedicated-server graceful shutdown/save completion. Stop is implemented, but tests do not stop a running game.

## Reproduce API smoke

The optional tools/PanelApiSmoke harness needs .NET 10 and an existing ServerPanel.App.dll. It does not modify the panel repository.

After building Java test classes:

```powershell
dotnet run --project tools/PanelApiSmoke -p:PanelAssembly="<path to ServerPanel.App.dll>" -- "<path to java.exe>" "<absolute ServerPanelBridge project path>"
```

It allocates a temporary loopback port and uses memory storage only. The process exits after checking the received heartbeat.

## 0.1.2 MLOS fix

Nine compatibility assertions passed in the installed game's Kahlua runtime with isolated MLOS API fixtures: idempotent hook, local ID/order preserved, no warning, backslash syntax, metadata lookup, missing mods retained on original path, published ID passthrough, foreign metadata rejection, and unrelated items delegated.

Nineteen installer assertions cover the existing checks plus automatic menu deployment/activation, exclusion of menu Lua from the core mod/server INI, and no menu component in server-only mode. Live MLOS interaction after reinstalling remains a deployment check.

## 0.2.0 graceful stop

Eighteen control assertions cover strict JSON, occupied/empty-server policy, client guard, disabled controls, game-thread dispatch, duplicate delivery, durable replay protection with new lease tokens, start/restart rejection, expired leases, uncertain reservations, journal failure, oversized responses, bounded response-body timeout, and no persisted lease tokens.

The real existing ServerPanel API was tested with an isolated in-memory registry and temporary loopback port. It leased start/stop/restart commands to the Java bridge; stop with a simulated game adapter was acknowledged and unsupported actions were marked Failed. No actual game server was stopped or started, and no active panel data was modified.

## 0.2.1 client requirement regression

The twelve monitoring assertions and eighteen command assertions passed again. A new isolated test uses the installed Build 42 game JAR, game Java runtime, actual ZombieBuddy Exposer and Kahlua interpreter. Only game-state lists/flags are fixtures. It verifies real Lua exposure, client guard without runtime files, OnServerStarted and OnTickEvenPaused callback registration/execution, and the actual native ConnectionDetails.writeMods serializer. Before startup the fixture packet contains two required IDs; after startup it contains only the other mod, while the server runtime still retains ServerPanelBridge. Restoring the advertised ID is repaired by the paused callback at the next exclusion check.

This tests native packet construction and lifecycle wiring, not a live multiplayer join. No live server was launched or stopped. Reproduce with installer/test-bridge.ps1 after building. Update the server with the bundled installer and fully restart it before testing a client without the mod.
## 0.3.0 restart

Twenty-nine control assertions pass, including Windows argument parsing, PID/start identity mismatch refusal, restart preparation without game action, one-time arming/acknowledgement, journal replay protection, player-policy cancellation, failed preparation without shutdown or argument leakage, expired-lease cancellation, dead-helper refusal before QueueQuit and explicit partial-failure acknowledgement after shutdown was queued. Twelve monitoring assertions also pass.

Four isolated real-process cases passed on the installed game's Java runtime: armed helper launches a replacement after original exit; cancelled helper does not launch; timeout leaves no overlapping replacement; literal-quote arguments are refused before shutdown. Spaces, Unicode temporary paths and trailing backslashes survive replacement. The processes use a small fixture named zombie.network.GameServer, not the installed game implementation; no live game world was touched.

The existing compiled ServerPanel API on a temporary loopback listener and in-memory registry acknowledged stop and enabled restart with simulated game adapters. Start and default-disabled restart were marked Failed. Native optional-client/bridge regression is also run on the actual game/ZombieBuddy libraries.

Live save completion and a successful real dedicated-server restart remain deployment checks. No actual game server was restarted during development. Reproduce helper checks with installer/test-restart.ps1. Install the single EXE, enable restartEnabled in the preserved active configuration, and fully restart the server before testing the panel command.
## 0.3.1 restart diagnosis

All 29 control and 12 monitoring assertions pass. Seven isolated process scenarios pass on the installed game's Java runtime: armed launch, cancellation, old-process timeout, unsupported-quote refusal, a simulated replacement startup failure, an occupied UDP port, and a temporarily occupied port that is released. The last scenario verifies that exitCode=1 is recorded instead of only reporting that ProcessBuilder started a child.

The first supplied snapshot ended at LOADING ASSETS: START. The subsequent extended log reaches server network startup and reports Connection Startup Failed. Code: 5 (RakNet SOCKET_PORT_ALREADY_IN_USE). The log does not identify the port owner. The occupied-port fixture verifies cancellation without a replacement and records the actual Windows owner PID; the release fixture verifies eventual launch. Installed Build 42 bytecode confirms defaultPort/udpPort are passed to UdpEngine. These probes reduce duplicate launches but do not reserve ports throughout game loading. Live remote restart and the identity of its conflicting process remain unverified.
## 0.3.2 native Windows restart regression

A direct Java child launched while the installed game's RakNet sockets were open still could not bind the temporary UDP port after the original called native Shutdown and exited (BindException). The identical production-adapter fixture against the compiled a079a34/0.3.1 source cancelled relaunch due to unavailable ports. Against 0.3.2, the helper launched a replacement that successfully started actual RakNet on both original temporary ports and exited normally. Tests use installed Build 42 classes, RakNet64.dll, ZNetNoSteam64.dll and the game's Java 25 runtime; no actual game world, Steam login, active dedicated server or panel was launched or stopped.

All seven existing separate-process scenarios pass with the Windows proxy: armed launch, cancellation, old-process timeout, quote refusal, early replacement failure, occupied-port cancellation with owner PID, and released-port launch. The 29 control and 12 monitoring assertions also pass. Reproduce the native case with installer/test-native-restart.ps1; its optional BridgeJar and ExpectBlocked arguments support comparison with an earlier JAR. A successful live remote world startup remains a deployment check.

## 0.4.0 updated panel protocol

18 compatibility assertions cover shared GUID-N identity, heartbeat/query/ACK session fields, foreign-session and expired-TTL refusal before reservation/preparation, tick-time TTL cancellation, ACK after TTL within the lease, heartbeat-gated bounded output queue, batch sizing, text/stream validation, rejected-batch discard, output timeout, bridge logger lifecycle, disabled output, fragmented UTF-8/CRLF/partial-line console capture, unchanged original bytes, and stream restoration.

The actual existing ServerPanel 1.13.1 compiled API was run on a temporary loopback port with an isolated in-memory registry. Java heartbeat and stdout/stderr/log output were accepted; simulated stop and enabled restart were acknowledged; start/default-disabled restart were refused. A second JVM generated a different session, cancelled the previous leased stop, and performed no game action. Old-session polling, ACK and output each returned 409. No desktop panel database or live game process was changed. Reproduce using tools/PanelApiSmoke with PanelAssembly pointing at an existing built panel DLL; this is protocol validation, not an observed GUI or live dedicated-server test.

## 0.4.1 additive config migration

Migration fixtures verify the canonical commented template, no writes/mtime change for complete configs, preserved explicit flags and endpoint/name settings, exact original text prefix, comments/unknown/blank keys, CRLF, exact backups, repeat-start idempotence, UTF-8 BOM, escaped keys, colon syntax, multiline values, trailing continuation, duplicate-key semantics, and invalid-value/invalid-UTF-8 refusal. Windows fixtures cover read-only incomplete/complete configs, failed atomic replacement while deletion is denied by an open handle, retained complete backup, and successful recovery after unlocking. Fixtures use temporary directories; no active server configuration is changed.
