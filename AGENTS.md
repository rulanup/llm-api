# AGENTS.md

LLM API: NeoForge 1.21.1 mod exposing a block / NBT manipulation SDK + HTTP endpoint. Consumer projects (`LLM Era` at `../LLM Era`) reference it as a composite build. See `README_API.md` for the user-facing API reference.

## Build

```
.\gradlew.bat compileJava     # fastest correctness check
.\gradlew.bat build           # full build, outputs build/libs/llmapi-1.0.0.jar
.\gradlew.bat runServer       # dev server; HTTP server binds on configured port
```

- Java 21 (toolchain pinned in `build.gradle`).
- Versions live in `gradle.properties` (`mod_version=1.0.0`, `neo_version=21.1.234`, `minecraft_version=1.21.1`).
- CI runs `./gradlew build` on JDK 21 (see `.github/workflows/build.yml`).
- No tests, no lint config, no pre-commit. CI only runs `build`.

## Project layout

```
src/main/java/com/example/llmapi/
├── LlmApiMod.java           # @Mod entry; binds ServerStartedEvent -> ApiHttpServer.start()
├── api/                     # Public SDK surface (what other mods import)
│   ├── BlockApi.java        # Static facade: place/break/readNbt/writeNbt/batch + Async variants
│   ├── BlockPlacement.java  # Immutable (pos, blockId, state, snbt, replace)
│   ├── BlockQuery.java      # Read result snapshot
│   ├── NbtPatch.java        # (mode, path, value) for read/write/merge/replace
│   ├── DimensionRef.java    # Lazy-resolved dimension id
│   ├── Operation.java       # One batch entry
│   └── ApiResult.java       # Uniform return: code, message, data, httpStatus()
├── core/                    # Implementation
│   ├── BlockPlacer.java     # setBlock + NBT application (must run on main server thread)
│   ├── BlockStateParser.java# stateProps map -> BlockState
│   ├── NbtPath.java         # /data-style path parser (Name + Index steps)
│   ├── NbtCodec.java        # SNBT <-> Tag; wrap CommandSyntaxException as IllegalArgumentException
│   ├── DimensionResolver.java
│   └── CommandProtocol.java # LLMEPI_PLACE / LLMEPI_NBT / LLMEPI_BREAK parser+executor
├── create/                  # Create compat (no hard dep, runtime probe only)
├── http/                    # JDK HttpServer; one Handler per endpoint
│   ├── ApiHttpServer.java   # Lifecycle; binds in start(), releases port on stop()
│   ├── TokenAuth.java       # Multi-token Bearer, constant-time compare, SHA-256 fingerprint logging
│   ├── HealthHandler.java PlaceHandler.java NbtHandler.java BatchHandler.java NotFoundHandler.java
│   ├── HttpUtil.java        # JSON envelope, request body read, error responses
│   └── RequestLogger.java   # Per-request line to logs/latest.log
├── config/ApiConfig.java    # ModConfigSpec; register on SERVER type
└── util/Json.java           # Gson wrapper (Minecraft ships Gson)
```

## Critical invariants

- **Main thread only for mutators.** `BlockApi.place/breakBlock/writeNbt` throw `IllegalStateException` off-thread. Use `BlockApi.*Async` (auto-schedules via `server.execute()`) or call from a known main-thread context. Read methods (`readNbt`, `getBlockState`) do not require main thread.
- **NBT serialization is round-tripped through `BlockEntity.saveWithFullMetadata` / `loadWithComponents`.** Don't write NBT directly to disk or NMS structures.
- **HTTP server lifecycle:** `ApiHttpServer.start()` runs in `ServerStartedEvent`; `stop()` runs in `ServerStoppingEvent`. Both are registered on the NeoForge game event bus via `NeoForge.EVENT_BUS.register(this)` in the `LlmApiMod` constructor.
- **Auth:** `tokens = []` in `llmapi-server.toml` triggers random UUID-based token generation on first launch (`ApiConfig.effectiveTokens()` writes back to the toml). To use a fixed token for testing, write `tokens = ["my-fixed-token-32chars-aaaaaa"]` directly to `llmapi-server.toml` and restart. Only the SHA-256 prefix is logged, not the token.
- **Command protocol prefix is `LLMEPI_`** (deliberately distinct from LLMEra's `LLMERA_`). When extending `CommandProtocol`, update `README_API.md` and the docstring in `CommandProtocol.PREFIX`.

## HTTP endpoint contract

- Bound to `127.0.0.1:8765` by default. Set `bind=0.0.0.0` + `allowLan=true` for LAN access.
- All endpoints accept and return `application/json; charset=utf-8`.
- All write endpoints require `Authorization: Bearer <token>`.
- Error codes: 400 `BAD_REQUEST` (malformed JSON / bad NBT path), 401 `UNAUTHORIZED` (missing or wrong Bearer), 404 `NOT_FOUND` (unknown dimension / block / no block entity at pos), 409 `CONFLICT` (chunk not loaded, target not empty when `replace=false`), 500 `INTERNAL_ERROR`, 503 `INTERNAL_ERROR` (server not ready), 504 `INTERNAL_ERROR` (main thread did not respond in time).
- **409 from a fresh dev world usually means the target chunk isn't loaded.** Walk within render distance of the player first, or call the endpoint from a tool-link station / script that forces chunk load via `level.getChunk(pos)`.

## Conventions

- No external runtime dependencies. JSON via Gson (Minecraft). HTTP via `com.sun.net.httpserver.HttpServer`. No Netty, no Spring.
- Reflection is used **only** in `create/SequencedGearshiftAdapter.java` to avoid forcing a hard Create dep. Don't add reflection elsewhere without a written reason.
- All public SDK methods return `ApiResult<T>`; never throw past the API boundary except `IllegalStateException` for off-thread calls and `IllegalArgumentException` for bad NBT paths.
- Logs must never contain the full API key. Use the SHA-256 fingerprint (`TokenAuth.fingerprint`).

## Verification

- `gradlew compileJava` — fastest correctness check.
- `gradlew runServer` — boots a dev server; HTTP endpoint starts on the configured port. The integrated client and server share `run/` (not `run-client/`); `runClient` reuses the same `run/`. Don't try to keep them on separate working directories.
- No automated tests; verify manually against a dev world when changing mutator logic.
- Manual HTTP smoke test (after `runServer` is up):
  ```
  curl http://127.0.0.1:8765/api/health
  curl -X POST http://127.0.0.1:8765/api/place \
    -H "Authorization: Bearer <token>" \
    -H "Content-Type: application/json" \
    -d '{"x":0,"y":64,"z":0,"block":"minecraft:stone","replace":true}'
  ```

## Quirks

- `TagParser.parseTag` throws checked `CommandSyntaxException`. `NbtCodec.parse` wraps it as `IllegalArgumentException` so callers don't need a brigadier dependency.
- `Property.getName(value)` requires an unchecked cast on the value parameter; the two affected call sites are in `BlockStateParser` and `BlockPlacer`. Do not "clean up" these casts.
- `CompoundTag.remove(String)` returns `void` in 1.21.1, so `NbtPath.NameStep.remove` reads before removing.
- `defineList` for `ModConfigSpec` takes `(String, List<? extends T>, Predicate<Object>)` — no `Class` parameter. See `ApiConfig.tokens`.
- NeoForge 1.21.1 MenuType construction for entity-based menus: use `IMenuTypeExtension.create(IContainerFactory)` and the `openMenu(provider, buf -> buf.writeBlockPos(pos))` overload, otherwise the client-side `ClientboundOpenScreenPacket` receives a null buffer and disconnects. (Affects consumers that add new container screens.)
- `ApiConfig.effectiveTokens()` mutates the toml via `SERVER.tokens.set(current)` if it was empty. The full token value is never logged — only the SHA-256 prefix. If you need to recover a token, read it back from the toml file directly (after restart).

## Files NOT to commit

`build/`, `run/`, `.gradle/`, `repo/`, `src/generated/`, `logs/`, `*.iml`, `.idea/`. Already in `.gitignore`.

## Consumer projects

- `C:\Users\email\idea-projects\LLM Era` (LLMEra v1) uses a Gradle composite build to depend on this mod: `includeBuild(...)` in its `settings.gradle` + `implementation "com.example.llmapi:llmapi:1.0.0"`. Any change to the public `api/` surface ripples to that project; minor/behavioral changes need a coordinated rebuild.
- Consumers also reach the `BlockApi` facade via `com.example.llmapi.api.*` only. Don't move SDK classes between packages without updating the consumer.
