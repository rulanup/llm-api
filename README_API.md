# LLM API

Generic block / NBT manipulation SDK for LLM-driven Minecraft mods.
Built for Minecraft 1.21.1 on NeoForge 21.1.x.

This mod is a **library** for other mods to use. It does not add any
in-world blocks. It exposes:

- A static Java SDK (`com.example.llmapi.api.BlockApi`) for other mods
  to call from the server thread.
- An HTTP endpoint with Bearer token auth, so an LLM agent can drive
  the world directly from outside the game.
- A command protocol (`LLMEPI_*`) that other LLM mods can scan for in
  LLM response text.

It is intentionally independent of Create. The Create integration
shipped with LLMEra is replicated as a runtime probe (no hard
dependency) so a downstream LLM mod can describe Create's sequenced
gearshift as a "programmable" tool target.

## Configuration

The config file is `config/llmapi-server.toml`:

```toml
[server]
    # 0 disables the HTTP server
    port = 8765
    # 127.0.0.1 = localhost only. Use 0.0.0.0 only with allowLan=true.
    bind = "127.0.0.1"
    allowLan = false
    # Multiple accepted Bearer tokens. Empty = one is auto-generated on first launch.
    tokens = []
    # Max NBT content length recorded in logs (characters).
    nbtSnbtLogTruncate = 200
    # 0 = off, 1 = errors only, 2 = all requests.
    requestLogLevel = 2
```

When `tokens` is empty, a random 32-char token is generated on first
launch and written back to the file. The token's SHA-256 prefix is
printed to `logs/latest.log`; the full token only lives in the toml.

## HTTP API

All endpoints accept and return `application/json; charset=utf-8`.
All write endpoints require `Authorization: Bearer <token>`.
The server is bound to `127.0.0.1` by default. Set `allowLan=true` and
`bind=0.0.0.0` to expose it to your LAN.

### `GET /api/health`

```json
{
  "ok": true,
  "mod": "llmapi",
  "version": "1.0.0",
  "createLoaded": true,
  "java": "21.0.1",
  "commandPrefix": "LLMEPI_"
}
```

### `POST /api/place`

```json
{
  "x": 100, "y": 64, "z": 200,
  "dimension": "minecraft:overworld",
  "block": "minecraft:oak_stairs",
  "state": {"facing": "east", "half": "bottom", "shape": "straight"},
  "nbt": "{\"Text1\":\"{\\\"text\\\":\\\"hi\\\"}\"}",
  "replace": true
}
```

Successful response:

```json
{
  "ok": true,
  "blockState": "minecraft:oak_stairs[facing=east,half=bottom,shape=straight]",
  "dimension": "minecraft:overworld",
  "pos": [100, 64, 200]
}
```

Error envelope:

```json
{"ok": false, "code": "BAD_REQUEST", "message": "Unknown block: minecraft:foo"}
```

### `POST /api/nbt`

Read:

```json
{ "x": 100, "y": 64, "z": 200, "dimension": "minecraft:overworld", "mode": "read" }
```

→ `{"ok":true,"nbt":"{Count:64b,id:\"minecraft:dirt\"}", "dimension":"..."}`

Write with path:

```json
{
  "x": 100, "y": 64, "z": 200,
  "mode": "write",
  "path": "Items[0].Count",
  "value": "64b"
}
```

Replace whole NBT:

```json
{ "x": 100, "y": 64, "z": 200, "mode": "replace", "value": "{...}" }
```

Merge into the root (or into a sub-compound via `path`):

```json
{ "x": 100, "y": 64, "z": 200, "mode": "merge", "value": "{CustomName:'{\"text\":\"hi\"}'}" }
```

NBT path syntax mirrors MC `/data`:
- `Count`
- `Items[0].Count`
- `Items[0].tag.display.Name`

### `POST /api/batch`

```json
{
  "dimension": "minecraft:overworld",
  "operations": [
    { "op": "place", "x": 1, "y": 64, "z": 1, "block": "minecraft:stone" },
    { "op": "place", "x": 2, "y": 64, "z": 1, "block": "minecraft:oak_stairs", "state": {"facing":"east"} },
    { "op": "nbt", "x": 2, "y": 64, "z": 1, "mode": "read" },
    { "op": "break", "x": 1, "y": 64, "z": 1 }
  ]
}
```

Returns per-op status:

```json
{ "ok": true, "total": 4, "succeeded": 4, "dimension": "minecraft:overworld",
  "results": [
    {"ok": true, "code": "OK"},
    {"ok": true, "code": "OK"},
    {"ok": true, "code": "OK", "message": null},
    {"ok": true, "code": "OK"}
  ]
}
```

Maximum 1024 operations per request. All ops run sequentially on the
main server thread.

### Error codes

| HTTP | `code`              | meaning |
|------|---------------------|---------|
| 200  | `OK`                | success |
| 400  | `BAD_REQUEST`       | malformed JSON, missing fields, bad NBT path |
| 401  | `UNAUTHORIZED`      | missing or wrong Bearer token |
| 404  | `NOT_FOUND`         | unknown dimension, unknown block, no block entity at pos |
| 409  | `CONFLICT`          | chunk not loaded, target position not empty (when replace=false) |
| 500  | `INTERNAL_ERROR`    | unexpected failure (logged) |
| 501  | `NOT_IMPLEMENTED`   | reserved (currently unused) |
| 503  | `INTERNAL_ERROR`    | server not ready (HTTP server running but `MinecraftServer` is null) |
| 504  | `INTERNAL_ERROR`    | main server thread did not respond within timeout |

## Java SDK

```java
import com.example.llmapi.api.BlockApi;
import com.example.llmapi.api.ApiResult;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import java.util.Map;

ServerLevel level = server.overworld();
BlockPos pos = new BlockPos(100, 64, 200);

ApiResult<Void> r = BlockApi.place(level, pos,
        "minecraft:oak_stairs",
        Map.of("facing", "east", "half", "bottom"));

if (!r.isOk()) {
    log.warn("place failed: {} {}", r.code(), r.message());
}
```

### NBT

```java
import com.example.llmapi.api.NbtPatch;

ApiResult<String> nbt = BlockApi.readNbt(level, pos);
String snbt = nbt.data();

ApiResult<Void> wrote = BlockApi.writeNbt(level, pos,
        NbtPatch.write("Items[0].Count", "64b"));
```

### Async (auto-scheduled to main thread)

```java
BlockApi.placeAsync(level, pos, "minecraft:stone")
       .thenAccept(r -> log.info("placed: {}", r.isOk()));
```

All mutating methods throw `IllegalStateException` when called off the
main server thread; use the `*Async` variants or wrap the call in
`server.execute(...)`.

## LLMEPI Command Protocol

A downstream LLM mod can pipe LLM response text through
`com.example.llmapi.core.CommandProtocol` to execute embedded commands
and strip them from the visible reply. Commands use the prefix
`LLMEPI_` to coexist with other mods' protocols.

Recognised commands:

```
[[LLMEPI_PLACE  x=10 y=64 z=20 block=minecraft:stone state=facing=east,half=bottom nbt={...} replace=true]]
[[LLMEPI_NBT    x=10 y=64 z=20 mode=write path=Items[0].Count value=64b]]
[[LLMEPI_NBT    x=10 y=64 z=20 mode=read path=Items[0].Count]]
[[LLMEPI_NBT    x=10 y=64 z=20 mode=merge path=Items[0] value={Slot:0b,id:"minecraft:dirt",Count:1b}]]
[[LLMEPI_NBT    x=10 y=64 z=20 mode=replace value={...}]]
[[LLMEPI_BREAK  x=10 y=64 z=20]]
```

`state` is a comma-separated `key=value` list. `nbt` is SNBT; quote it
with double-quotes inside the bracket, or escape with backslashes
inside the JSON. `path` follows the same grammar as `/api/nbt`.

Example call site:

```java
import com.example.llmapi.core.CommandProtocol;

String responseText = "I'll place a staircase.\n[[LLMEPI_PLACE x=0 y=64 z=0 block=minecraft:oak_stairs state=facing=east,half=bottom]]";
CommandProtocol.Outcome out = CommandProtocol.apply(responseText, server.overworld());
player.sendSystemMessage(Component.literal(out.visibleResponse));
for (String line : out.log) {
    LOGGER.info(line);
}
```

## Create integration

`com.example.llmapi.create.CreateProbe` is `true` only when Create is
loaded. `CreateProbe.isSequencedGearshift("create:sequenced_gearshift")`
returns `true`. `SequencedGearshiftAdapter` lets a downstream LLM mod
locate and describe a Create sequenced gearshift via its block-entity
type id, so it can be classified as a "programmable" tool target
without forcing a hard Create dependency.

## Build

```
.\gradlew.bat build
```

Output jar: `build/libs/llmapi-1.0.0.jar`.

## Logging

All API calls log a single line per request to `logs/latest.log`:

```
[llmapi:http] POST /api/place from=127.0.0.1 status=200 elapsed=42ms
[llmapi:http] POST /api/nbt from=127.0.0.1 status=401 elapsed=2ms
```

NBT content in the request log is truncated to `nbtSnbtLogTruncate`
characters (default 200) to keep logs small.
