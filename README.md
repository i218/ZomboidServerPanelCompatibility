# ServerPanelBridge

A separate Project Zomboid Build 42 mod that reports dedicated-server health to ServerPanel through ZombieBuddy.

[Русская версия](README.ru.md)

## What you get

The server appears in ServerPanel under **Game servers**, with a heartbeat every five seconds:

| Metric | Meaning |
| --- | --- |
| Online | ServerPanel received a recent heartbeat |
| Health | Healthy while server-loop callbacks continue, including empty-server pause; Degraded after 20 seconds without a callback |
| CPU | JVM process CPU, normalized across logical processors; first sample is zero |
| Memory | Used JVM heap in MiB, not total process memory |
| Uptime | JVM uptime |

Online alone does not prove the game loop is responsive. A running reporter can show Online together with Degraded. If the process exits or the panel cannot be reached, the panel eventually marks the host offline.

Networking runs on a daemon worker. Panel outages cannot block game ticks. Requests have a two-second timeout; retries back off to 30 seconds and recover automatically.

This release implements **monitoring, Output forwarding, session-aware commands, and opt-in graceful stop/restart**. Start still requires an external supervisor. Player counts and Workshop updates need additional fields in ServerPanel and are not sent as unrelated metrics.

## Install with one executable

1. Close the game and dedicated server.
2. Run **ServerPanelBridge-Setup.exe**. Administrator rights are not normally needed.
3. Select the server account's actual Zomboid cachedir and an existing server INI from the list.
4. Leave dedicated-server setup enabled. Optionally enable the client-list checkbox if you edit server settings through the game.
5. Click Install, then restart the dedicated server and start ServerPanel on the same machine.

The executable contains the mod and compiled JAR. It installs the files, enables ZombieBuddy and ServerPanelBridge in the selected INI, creates the runtime config if absent, and verifies written files. Existing runtime configuration and other INI settings are preserved. Updated files are backed up under `<cachedir>/ServerPanelBridge-installer-backups/`; failed writes trigger rollback.

ZombieBuddy's Java agent must already be installed, and ServerPanel is installed separately. The installer does not start or stop the game or panel, change WorkshopItems, or bypass ZombieBuddy's trust checks. The JAR remains unsigned.

The default installs only for the dedicated server. The optional client checkbox makes this same local mod available in the client's mod list and enables it in `mods/default.txt`. Players joining the server do not need ServerPanelBridge: after loading, its adapter removes only its own entry from the Build 42 connection's required-mod list, retaining the server runtime and other mods. If that API is unsupported, the log reports the failure; a live join without the mod is still the final compatibility check. ZombieBuddy's own client requirements are unchanged.

CLI for the same executable:

```powershell
.\ServerPanelBridge-Setup.exe --silent --data-dir "D:/PZData" --server-name "servertest" --mode server
```

Modes: `server` (default), `client`, `both`. Exit code 0 means success; failures return 1 and write a diagnostic file in Windows Temp. Use a real directory path rather than a junction/symlink.

### Manual alternative

Extract **ServerPanelBridge-mod.zip** into `<cachedir>/mods/`, so that `mods/ServerPanelBridge/42/mod.info` exists. Add the mod after ZombieBuddy in the server INI `Mods=` list, preserving other entries. Example: `Mods=\ZombieBuddy;\ServerPanelBridge`.

No Steam Workshop item has been published for this project; local installation does not need a new WorkshopItems entry.

## Configuration and logs

At dedicated-server startup the mod creates:

```text
<Zomboid cachedir>/ServerPanelBridge/<serverName>/ServerPanelBridge.properties
<Zomboid cachedir>/ServerPanelBridge/<serverName>/ServerPanelBridge-0.log
```

The cache directory comes from the game, so a custom `-cachedir` is supported. Logs rotate across three files of up to 1 MiB. Change configuration with the server stopped, then restart.

| Setting | Default | Notes |
| --- | --- | --- |
| enabled | true | Set false to disable reporting |
| panelUrl | http://127.0.0.1:5088 | HTTP literal loopback only; panel must run on the same machine |
| hostId | pz-<serverName> | Stable ASCII identity; choose a unique value for each server |
| hostName | Project Zomboid: <serverName> | Display name; UTF-8 supported |
| category | Game servers | Group in ServerPanel |
| environment | Production | Display label |
| address | Local dedicated server | Display label, not a connection target |
| heartbeatSeconds | 5 | Allowed: 5–10 |
| requestTimeoutSeconds | 2 | Allowed: 1–5 |
| staleTickSeconds | 20 | Allowed: 10–300 |
| maxRetrySeconds | 30 | Maximum retry delay |
| outputEnabled | true | Send bridge events to the panel Output tab |
| captureConsoleOutput | false | Also tee Java stdout/stderr; may contain private data |

A copy of the defaults is in [config/ServerPanelBridge.properties](config/ServerPanelBridge.properties). The runtime file is created automatically; the copy in the mod directory is a reference, not the active configuration. On server startup, missing settings are added to the runtime file automatically, preserving your existing settings.

With the default console capture disabled, reporting does not read player lists, chat, INI contents or other mods' log files. Console capture is opt-in and does not redact player data or secrets. Optional JVM overrides: `-Dserverpanelbridge.config=<absolute file>` and `-Dserverpanelbridge.cachedir=<absolute directory>`.

## Try it

1. Start both applications and wait for the host to appear.
2. Verify CPU, heap memory and uptime change while the server runs.
3. Exit ServerPanel, keeping the server running. The bridge should log a connection warning; the game should keep running.
4. Start ServerPanel again. Reporting should resume automatically within the configured retry delay plus the request timeout.
5. Stop the dedicated server normally. Its host should become offline after ServerPanel's heartbeat freshness window (currently 15 seconds).

No test requires killing the game process. Changing `enabled=false` and restarting should suppress all outgoing heartbeats.

## Build

Use JDK 11 or newer with Gradle 8.7 (Java 11 bytecode):

```powershell
.\gradlew.bat --no-daemon clean build installer
```

Outputs:

- `build/libs/ServerPanelBridge.jar`
- `42/media/java/ServerPanelBridge.jar`
- `build/distributions/ServerPanelBridge-mod.zip`
- `build/distributions/ServerPanelBridge-Setup.exe`

The build runs dependency-free behavioral tests for configuration, encoding, HTTP failures/timeouts, game-tick health, worker lifecycle and client isolation. Test fixtures stay out of the production JAR.

See [VERIFICATION.md](VERIFICATION.md) for validation evidence and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for integration boundaries.

## Compatibility

Adapters use the locally verified Build 42 API: `GameServer.server`, `GameServer.serverName`, and `ZomboidFileSystem.instance.getCacheDir()`. ZombieBuddy registration uses `Exposer.exposeClass(Class, String)`.

The JAR uses the neutral `42/media/java/` path because early ZombieBuddy environment detection can skip the server-specific Java directory. Server Lua callbacks and the Java dedicated-server guard prevent client reporting. An unsupported API disables initialization and logs an error.

A live dedicated-server launch remains the final deployment check; a successful build or API test does not establish that ZombieBuddy accepted the unsigned JAR.

## Local mods in MLOS (0.1.2)

When the client checkbox is selected, the same installer also installs and enables ServerPanelBridgeMenu. It retains the installed local ServerPanelBridge ID when MLOS saves server settings, without inventing a Steam Workshop ID. Missing mods and published items remain handled by MLOS. Enable the menu component in the main-menu Mods list and restart the game after updating.

The menu component must stay out of the server INI. Its Lua files are kept separate from the core mod to preserve optional client installation with game checksums enabled. The installer handles both directories automatically. Manual installation requires copying the bundled client-support/ServerPanelBridgeMenu into the client mods directory and enabling it there.

UnknownFormatConversionException in other mods' translated settings is a separate issue; this compatibility module defines no translations or options panel.


## Graceful stop (0.2.0)

The host Stop command is supported through the panel command queue. In the server's active ServerPanelBridge.properties, set:

```properties
commandsEnabled=true
stopEnabled=true
stopWhenPlayersOnline=false
```

Restart the dedicated server after editing. Existing configs are preserved by the installer; missing keys use these defaults except stopEnabled defaults to false.

Commands are polled every five seconds on a separate worker. Stop runs on the game thread using the verified public Build 42 ServerMap.QueueQuit() API. The game performs its normal queued save/quit procedure. With stopWhenPlayersOnline=false, a nonempty server refuses stop and reports the reason to the panel. To explicitly allow stopping a nonempty server, set stopWhenPlayersOnline=true.

The panel acknowledgement means **shutdown was requested**, not that saving or process exit has completed. The host should later become offline. The process may exit before acknowledgement reaches the panel; an on-disk command journal prevents the same command stopping a subsequent server session.

Start is explicitly rejected: starting a stopped JVM requires ServerPanel's local process management or another supervisor. Restart is available with the bundled helper as described below. commandsEnabled=false disables command polling; enabled=false disables panel reporting/polling; explicitly authorized local mod requests remain available. The server is never killed or terminated with System.exit. On Windows a fixed PowerShell WMI query reads the original process arguments; no configurable shell command is executed.

The journal is stored beside the runtime config in commands/. It retains up to 1,000 command outcomes without lease tokens. An uncertain reserved command is not automatically replayed; send a new command after checking the server. Preserve the journal when updating.

## Client optional mode (0.2.1)

Update the server with the new installer, then fully restart the dedicated server. Ordinary joining players do not need this mod or its menu component. Keep ServerPanelBridge in the server INI; keep ServerPanelBridgeMenu out of it.

The adapter now runs on OnServerStarted before connections are handled, and on OnTickEvenPaused afterward. With PauseEmpty=true, normal OnTick does not run on an empty paused server; waiting for it left the mod mandatory for the first client. Paused callbacks also allow opted-in Stop commands to run on an empty server. The required-mod exclusion is rechecked every five seconds, independently of whether reporting is enabled.

Check the server startup log for:

```text
[ServerPanelBridge] Client optional mode confirmed: ServerPanelBridge removed from required mod list
```

If that line is absent, check the preceding ZombieBuddy/bridge errors. This fix preserves other required mods and does not disable game checksums. A join from a client without ServerPanelBridge remains the deployment check.

To run the isolated native bridge/handshake regression test after building (requires installed Build 42 and ZombieBuddy):

```powershell
.\installer\test-bridge.ps1 -GameDirectory "<ProjectZomboid directory>" -ZombieBuddyJar "<ZombieBuddy.jar path>"
```

The test uses temporary configuration with reporting disabled. It does not launch or stop a game server or modify installed mods.
## Restart (0.3.0)

The same installer and JAR now include a one-shot restart helper. In the active server configuration set:

```properties
commandsEnabled=true
restartEnabled=true
restartExitTimeoutSeconds=180
stopWhenPlayersOnline=false
```

Fully restart the server after updating/editing the config, then use Restart in ServerPanel. restartEnabled defaults to false and is independent of stopEnabled. Both actions share the player policy: a nonempty server refuses shutdown unless stopWhenPlayersOnline=true. No countdown or player announcement is implemented.

The worker first captures the current Java executable, original arguments, working directory and process identity, and starts a separate lightweight helper JVM from the bundled JAR. Once ready, the game thread requests normal QueueQuit and arms the helper with a one-byte pipe message. The helper waits for that exact process to exit, then launches one replacement JVM. If it fails to exit within restartExitTimeoutSeconds (10–600), restart is cancelled; the helper never kills it. Preparation/lease/player-policy failures leave the server running and cancel the helper.

Original launch parameters are passed through a private pipe, not written to scripts or journals. Windows uses a fixed, bounded PowerShell/CIM query because ProcessHandle omits arguments there; parsing follows [Microsoft's Windows argument rules](https://learn.microsoft.com/en-us/cpp/c-language/parsing-c-command-line-arguments?view=msvc-170). Java exposes process metadata as optional values ([Java API](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/lang/ProcessHandle.Info.html)); unavailable metadata causes refusal before shutdown. A direct Java launch with zombie.network.GameServer is required. Argument files (`@...`) and literal double quotes inside decoded arguments are refused, because they cannot safely be replayed by this implementation. Spaces, Unicode paths and trailing backslashes were tested.

Do not combine this helper with an external supervisor's automatic restart: let one component own relaunch. Managed process trees/jobs may terminate child helpers; use the supervisor's restart in that setup. The helper inherits the server account and environment. Replacement output is appended to restart-console.log beside the runtime config; helper results go to restart-helper.log. These files do not rotate automatically. The relaunched server has no interactive console input; use the panel or normal game administration.

A successful panel acknowledgement means shutdown was queued and the helper was armed, not that saving or the new startup completed. Check the replacement server logs and returning heartbeat. If the JVM exits before acknowledgement, the existing durable journal prevents replay; an uncertain command must not automatically restart the next session. Start remains outside the mod.

Isolated process test (no real game server is started):

```powershell
.\installer\test-restart.ps1 -JavaExecutable "<game jre64/bin/java.exe>"
```
## Restart diagnostics (0.3.1)

restart-helper.log now records dated stages, the replacement PID, and its eventual exit code in decimal and hexadecimal. A process still alive after ten seconds is reported as running, not ready. The helper remains alongside the replacement only to observe exit; it does not retry or terminate it. Console output permissions are checked before shutdown, and cancellation/arming failures have explicit stage messages without launch arguments.

If the console log stops during loading and the host never appears, distinguish an exited process from one still loading or stuck. The panel heartbeat starts after server-loop initialization. A log ending at LOADING ASSETS: START alone proves neither crash nor readiness. Preserve restart-helper.log, restart-console.log and the game's latest server DebugLog; for native crashes also inspect hs_err_pid*.log in the game's working directory. Do not launch a second server against the same save/ports until the replacement PID is checked.
After the original process exits, the helper checks the effective GameServer.defaultPort and GameServer.udpPort with exclusive UDP wildcard bind probes. It waits up to 30 seconds for release and cancels launch if either cannot be bound. On Windows, occupied-port diagnostics include ownerPid when Get-NetUDPEndpoint is available. The helper never stops that owner or changes ports. Unknown/invalid game port fields refuse preparation before shutdown. The check is conservative and does not reserve ports: another launcher can still claim them during game loading. Use a single restart owner.

Connection Startup Failed. Code: 5 corresponds to SOCKET_PORT_ALREADY_IN_USE in [RakNet's startup result enum](https://github.com/facebookarchive/RakNet/blob/master/Source/RakNetTypes.h). Check ownerPid in restart-helper.log on the dedicated machine. A cancelled restart leaves the old server stopped; resolve the port conflict before starting it again.

After the original process exits, the helper checks the effective GameServer.defaultPort and GameServer.udpPort with exclusive UDP wildcard bind probes. It waits up to 30 seconds for release and cancels launch if either cannot be bound. On Windows, occupied-port diagnostics include ownerPid when Get-NetUDPEndpoint is available. The helper never stops that owner or changes ports. Unknown/invalid game port fields refuse preparation before shutdown. The check is conservative and does not reserve ports: another launcher can still claim them during game loading. Use a single restart owner.

Connection Startup Failed. Code: 5 corresponds to SOCKET_PORT_ALREADY_IN_USE in [RakNet's startup result enum](https://github.com/facebookarchive/RakNet/blob/master/Source/RakNetTypes.h). Check ownerPid in restart-helper.log on the dedicated machine. A cancelled restart leaves the old server stopped; resolve the port conflict before starting it again.

## Windows restart socket inheritance fix (0.3.2)

On Windows, the original Java ProcessBuilder launch allowed the helper to inherit RakNet's native socket handles. Even after the original server closed its sockets and exited, those copies could keep its UDP ports busy. The port check in 0.3.1 detected this condition but did not remove its cause.

The bundled Windows launcher now passes only the private stdin/stdout/stderr handles to the helper through an explicit Windows handle list. The short-lived launcher exits when either the original server or the helper exits, releasing its own inherited copies. The helper and replacement do not receive the old server sockets. It remains a one-shot graceful restart; no process kill or automatic retry is added. This uses the Windows .NET Framework runtime already used by the installer, and requires no additional installation. The launcher EXE is embedded in the JAR and extracted to the active runtime directory with a content-hash filename. Missing resources or launch failures refuse restart preparation before shutdown.

Install the updated EXE while the dedicated server is stopped, then start a clean server session with the new JAR. Existing failed processes from an older version must be identified separately; this update does not terminate them. Your runtime configuration is preserved. Test Restart only after the server is ready, then check its returning heartbeat.

Native regression test, using installed RakNet and Java with temporary ports and no game world or Steam login:

```powershell
.\installer\test-native-restart.ps1 -GameDirectory "<ProjectZomboid directory>"
```

The same fixture reproduced retained ports with the 0.3.1 code and successfully rebound both ports in the replacement with 0.3.2. This validates native socket transfer and process relaunch, not a full production world startup. Windows builds embed the launcher using the built-in .NET Framework C# compiler; builds on other platforms use the existing direct Java helper path.

## ServerPanel 1.12–1.13 compatibility (0.4.0)

Each JVM creates one random sessionId and sends it in every heartbeat, command poll, ACK and output batch. It changes after a server restart and is never stored in configuration. Stop/Restart from a different targetSessionId are ignored before preparing a helper or reserving a command. Both expiresAtUtc and leaseExpiresAtUtc are checked before execution, including on the game tick. An action begun on time can still ACK after its command TTL, as long as its original lease is valid. Older command responses without the new optional fields retain the existing lease checks. Sessions prevent stale commands; they are not authentication.

Open your host's **Output** tab to see new bridge events automatically after updating. For Java console output, add this to the active server-specific configuration and restart the server:

```properties
outputEnabled=true
captureConsoleOutput=true
```

This tees System.out/System.err without changing their original bytes. It does not import existing files, chat history, native C/C++ console output or writes to streams cached before capture. Other mods may bypass or replace these streams; the bridge restores them on shutdown only if it still owns them. Some logging setups can produce duplicates. Console text may contain player addresses, chat or credentials and is stored by the panel in plain text/backups. Enable only if that is acceptable.

Output has a separate daemon worker: up to 500 queued records, 100 per batch, 4000 UTF-16 characters per record. Oldest queued records are dropped when full. Incomplete console lines retain at most 16 KB until a newline or cleanup. Output waits for successful heartbeat registration and is best effort: rejected/timed-out batches are discarded without application-level replay, because the panel does not deduplicate. No startup backlog or shutdown flush is guaranteed; the panel retains only its latest 500 records. outputEnabled=false disables both sources. Existing runtime configs remain intact; missing new settings use the defaults above. Update the server's single EXE and fully restart it to activate the new JAR.

The integration fixture can use an existing built ServerPanel assembly without launching the desktop app or touching its database:

```powershell
dotnet run --project tools/PanelApiSmoke/PanelApiSmoke.csproj -c Release -p:PanelAssembly="<ServerPanel.App.dll path>" -- "<java.exe path>" "<bridge project root>"
```

It uses a temporary loopback port, an in-memory registry, separate test JVM sessions and simulated game actions. Live Output/UI and multiplayer deployment remain checks on your actual server.

## Automatic configuration upgrades (0.4.1)

After installing a new version, start the dedicated server normally. The bridge compares the active config with its bundled defaults and appends only missing keys. You no longer need to delete the old file to discover new options. Existing values, comments, custom keys, ordering, UTF-8/BOM and line endings are preserved; an existing key is never reset to a new default. A complete config is not written again and gets no new backup.

Before an upgrade, an exact copy is saved beside the active file as `ServerPanelBridge.properties.backup-<unique>.bak`. The updated file is written to a temporary sibling and replaces the original atomically. If validation, backup or replacement fails, the original is retained and initialization reports an error. Invalid existing values are not silently corrected. Edit configs with the server stopped; a detected concurrent change aborts migration. Keep backups private: they contain your settings, and are not deleted automatically.

New settings come from the same commented template bundled with the installer, so future additions are included in migration automatically. The installer still preserves an existing runtime config; additive migration happens at server startup, including when reporting is disabled. After migration, change the desired option and restart to apply it. Deleting an option makes it missing again and restores its default at the next startup; disable boolean settings explicitly with `false`.

## Optional requests from other mods (0.5.0)

Public JVM-local API: `io.serverpanelbridge.ModRestartApi.apiVersion()` returns 1; `requestRestart(requester, requestId)` returns QUEUED, ARMED or REJECTED_/FAILED_ status; `status(requester, requestId)` reads the session outcome. There is no HTTP endpoint for these requests. Request IDs are bounded, idempotent within the current JVM and capped at 128 outcomes; only one local request may be active. Names identify administrative policy, not a security boundary against code already executing in the JVM.

Set `restartEnabled=true` and `restartRequesters=WorkshopSentinel` to authorize the Sentinel adapter; the default empty requester list disables integrations. `stopWhenPlayersOnline` is still enforced on the game thread. Preparation and journal writes run on the control worker; stop/restart uses the same game-thread gate as panel commands. Helper preparation failure never queues shutdown, and duplicate requests cannot start another helper. ARMED means the existing restart mechanism was armed, not that startup succeeded. Results persist to the existing command journal without HTTP ACKs for local requests.

Local integration is independent from panel reporting/polling: `enabled=false` and `commandsEnabled=false` can be used with the explicit requester list and restart permission. It does not enable remote commands. WorkshopSentinel remains optional and is not bundled into Bridge. Use a single restart owner; do not run an independent auto-restart supervisor for the same process.

## Shared mod contract (0.5.1)

Other server mods use the same versioned [Local restart API v1](docs/MOD_RESTART_API.md), via Java reflection or the ZombieBuddy Lua global. There is no plugin-specific production route or required Sentinel dependency. Multiple exact requester names can be authorized; every request retains Bridge's existing player policy, common shutdown gate, helper, and journal. Concurrent duplicates reuse one outcome. Local requests expire 60 seconds after acceptance, and closure publishes a terminal status rather than leaving queued requests pending forever. The existing Sentinel Java adapter stays compatible with v1.
