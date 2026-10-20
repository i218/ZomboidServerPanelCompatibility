# Local restart API v1

ServerPanelBridge accepts optional restart requests from server-side Java and Lua mods. The contract does not register plugin classes, reference WorkshopSentinel, or depend on an HTTP listener. The caller owns detection/countdowns; Bridge owns the graceful shutdown and one replacement launch. Do not run another restart owner for the same process.

## Administrator settings

```properties
restartEnabled=true
restartRequesters=WorkshopSentinel;MaintenanceMod;BackupScheduler
```

Use exact requester names, separated by commas, semicolons or whitespace. Only list mods you intend to authorize; the default list is empty and wildcards are not supported. Names are policy labels, not authentication of code already running in the JVM. `stopWhenPlayersOnline=false` still refuses shutdown while players are online. A mod cannot bypass that policy. Local requests work with `enabled=false` and `commandsEnabled=false`; these flags control panel networking. They do not need a connection to ServerPanel.

The normal config migration adds missing options without resetting existing values. Install the updated Bridge and restart the dedicated server to activate the API.

## Java entry point

`io.serverpanelbridge.ModRestartApi` has three static methods:

```java
int apiVersion(); // currently 1
String requestRestart(String requester, String requestId);
String status(String requester, String requestId);
```

For optional integrations use reflection, so the caller still loads without Bridge:

```java
Class<?> bridge = Class.forName("io.serverpanelbridge.ModRestartApi");
if (!Integer.valueOf(1).equals(bridge.getMethod("apiVersion").invoke(null))) {
    throw new IllegalStateException("Unsupported Bridge API");
}
// Generate once for a new operation; retain the same ID for its retries/status.
String requestId = java.util.UUID.randomUUID().toString();
String state = (String) bridge.getMethod("requestRestart", String.class, String.class)
    .invoke(null, "MaintenanceMod", requestId);
String current = (String) bridge.getMethod("status", String.class, String.class)
    .invoke(null, "MaintenanceMod", requestId);
```

Handle missing/incompatible API explicitly and keep the mod's other features independent. In delegation mode, a rejection is not permission to perform an independent shutdown/relaunch.

## Lua entry point (Bridge 0.5.1+)

ZombieBuddy exposes the same contract through the existing global `ServerPanelBridge`:

```lua
if not isServer() then return end
if not ServerPanelBridge or not ServerPanelBridge.restartApiVersion then
    return -- Bridge unavailable; leave unrelated functionality running
end
if ServerPanelBridge.restartApiVersion() ~= 1 then return end

-- One ID for this operation; do not generate a new operation on every tick.
local requestId = "maintenance-operation-001"
local state = ServerPanelBridge.requestRestart("MaintenanceMod", requestId)
local current = ServerPanelBridge.restartStatus("MaintenanceMod", requestId)
```

The Lua and Java methods reach the same queue, allowlist and game-thread gate. Requests may be submitted from server callbacks without waiting for network, journal writes or helper preparation. Calls before server initialization can return `REJECTED_UNAVAILABLE`. Keep the same ID when checking or retrying that operation.

## Identity, expiry and bounds

- `requester`: 1–80 ASCII characters from `A–Z a–z 0–9 _ . -`.
- `requestId`: 1–64 ASCII characters from `A–Z a–z 0–9 _ -`.
- Identity is the pair `(requester, requestId)`. Different requesters can use the same ID.
- Duplicate calls, including simultaneous calls, reuse their stored outcome and never prepare another helper for that operation.
- One active local request, at most 128 stored local outcomes per JVM. Completed IDs are retained, not evicted; further new requests can be refused as busy after the cap is reached.
- Accepted requests have 60 seconds to begin execution, counted from acceptance, including time queued on the worker. Expired work cannot request shutdown. Checks run before preparation and again on the game tick.
- Idempotence/status are scoped to this JVM session. They are not a persistent operation service across server restarts. The existing disk journal records accepted execution/outcomes without panel ACKs.

## Status values

| Value | Meaning |
| --- | --- |
| `QUEUED` | Accepted; preparation or game-thread execution is still pending |
| `ARMED` | QueueQuit requested and the existing one-shot restart helper armed; saving, process exit and replacement readiness are not confirmed |
| `REJECTED_NOT_SERVER` | Public entry called outside a verified dedicated server |
| `REJECTED_UNAVAILABLE` | Bridge not initialized/started, service disabled or already closed |
| `REJECTED_DISABLED` | Restart not enabled or requester absent from the allowlist |
| `REJECTED_ID` | Invalid requester/request ID |
| `REJECTED_BUSY` | Another local request active, shutdown already requested, or outcome capacity reached |
| `FAILED_PREPARATION` | Work expired before preparation, journal reservation failed, or helper preparation failed; this request did not queue shutdown |
| `FAILED_EXECUTION` | Game-thread policy/expiry/validation/arming or execution failed; shutdown may already have been queued if arming failed |
| `FAILED_CLOSED` | Bridge closed before an outcome could be confirmed; not proof that the server remained running |
| `UNKNOWN` | No outcome for this pair in this JVM, or invalid lookup IDs |

`requestRestart` may return an existing queued/terminal outcome for a duplicate. `status` does not enqueue work and does not return `REJECTED_*`. For a new intent after a failure, investigate the reported failure and use a new request ID. Do not blindly resubmit new IDs or treat `ARMED` as completed startup. After closure, pending outcomes become terminal; inspect the normal server/helper logs when execution is uncertain.

## Verification boundaries

`genericModApiTest` uses independent requester names without any Sentinel classes/JAR. It covers Java/Lua forwarding, policy refusal, cross-requester identity, malformed identifiers, expiry while the worker is occupied, concurrent duplicates, shared shutdown gate and cleanup. `installer/test-bridge.ps1` exercises the methods in real ZombieBuddy/Kahlua with isolated game state. The existing optional Sentinel JAR fixture remains a consumer compatibility check, not a special production route. Full live server restart and multiplayer deployment remain separate checks.
