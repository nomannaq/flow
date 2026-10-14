# B03: Flow engine protocol v1

Branch: `feat/versioned-engine-protocol`. Rust owns the protocol and product state.
The Kotlin DTOs are a small explicit adapter using the pinned IDE's Gson. No
JetBrains or ACP types cross the Flow boundary. Swing and the project-service
subprocess bridge are implemented in B06; B03 supplies its contract and correlation helper.

## Wire profile

Flow uses [JSON-RPC 2.0](https://www.jsonrpc.org/specification) objects, one compact
UTF-8 JSON object per line. LF and CRLF are accepted. JSON string newlines must be
escaped. A complete final object without LF is accepted at EOF; incomplete JSON
gets a parse error. Batches, positional params, null request IDs and fractional
IDs are outside this negotiated profile. Params are objects and default to `{}`.

The engine uses Tokio and tokio-util's bounded `LinesCodec`, a single output
writer and a connection-local event loop. Split and coalesced reads do not change
message boundaries. Limits:

| Limit | v1 value |
| --- | --- |
| Input/output JSON frame, excluding LF | 1 MiB in UTF-8 bytes |
| Concurrent work requests | 32 |
| Distinct request IDs per connection | 65,536, then reconnect (expanded in B08 for session polling) |
| Default / maximum request timeout | 30,000 ms / 30,000 ms |
| Minimum timeout | 1 ms |
| Blocked output write | 5 seconds, then terminate |
| String IDs | 1–128 ASCII letters/digits or `._:-` |
| Numeric request IDs | Integers from −9,007,199,254,740,991 through +9,007,199,254,740,991 |

Numeric and string IDs are distinct. Never reuse an ID on one connection, even
after cancellation or timeout. Duplicates terminate the connection with a null-ID
protocol error, then cancel pending work; a duplicate error cannot impersonate the
original request's result. Exceeding the lifetime-ID budget follows the same close
policy. A recoverable ID is reserved even if the remaining fields are invalid.
Capacity exhaustion rejects a new work request, while cancellation and
shutdown remain available.

Invalid UTF-8 and oversized lines produce a null-ID invalid-request error and
terminate the connection. Malformed JSON gets a null-ID parse error; a later
correct frame can still be processed. Error messages are fixed text and never
include input, credentials, repository text or raw parser diagnostics. Logs use
stderr exclusively. Broken stdout or write timeout ends the process; pending
read-only futures are dropped. No background thread owns a second stdout writer.

## Handshake and methods

Send initialization as the first request (not a notification):

```json
{"jsonrpc":"2.0","id":"init-1","method":"engine.initialize","params":{"protocolVersion":1,"clientName":"Flow JetBrains","clientVersion":"0.1.0","projectId":"project-example","capabilities":{"editorContext":true,"historicalDiff":true,"nativeMerge":true,"commitSelection":false}}}
```

The result advertises protocol/engine versions, a random connection epoch, the
bound project ID, limits and capabilities. The project ID is an opaque host-supplied
binding; canonical filesystem identity/authorization belongs to B07. Capability
booleans describe supported behavior, not filesystem permission. Currently only
cancellation is always enabled. B06 enables `persistentEvents` when the engine
is launched with `--project-root PATH` (and optionally `--data-root PATH` for
tests). The canonical project history is opened at launch, before initialization.
Bare stdio launches remain side-effect free and advertise no durable events.
B08 Unix project-bound engines advertise `agentSessions`; an agent starts only
after an explicit `sessions.start`. Idempotent mutation endpoints remain false.
See [B08 session methods and admission semantics](acp-agent-sessions.md#ipc-additions).

The v1 version integer denotes the major wire contract; additive fields and
capabilities do not change it. Unsupported versions fail explicitly and allow a
new initialization request with a new ID. A successful initialization cannot be
repeated. Client name/version are nonempty UTF-8 strings of at most 128 bytes.
Unknown optional fields and host capability names are ignored. Required fields
with wrong types are rejected.

| Method | Direction and behavior |
| --- | --- |
| `engine.initialize` | Request; returns `InitializeResult` and binds the connection |
| `engine.health` | Request; requires `epoch`, returns version and project identity |
| `state.snapshot` | Project-bound request; returns a consistent count summary and `highWater` cursor |
| `events.replay` | Project-bound request; `{after: {storeId, sequence}, limit: 1..100}`; returns ordered events, `highWater` and `hasMore` |
| `engine.shutdown` | Request; requires `epoch`, cancels pending work, sends its cancellations then a final null result and exits |
| `requests.cancel` | Notification; requires matching `epoch` and `params.id`, cancels that pending request |

Post-initialization requests carry the returned `epoch` at envelope level. Replies
also carry it. Reconnecting starts a fresh epoch. Replies from a previous epoch
must not resolve current requests. Health accepts optional `waitMs` (0–30,000)
for bounded diagnostic waits; it has no side effects. Request envelopes optionally
set `timeoutMs`; the deadline starts when the engine admits work. Whichever
completion/cancellation/deadline is observed first resolves the request once. If
several become ready together, cancellation takes priority over deadline, then
successful completion.

```json
{"jsonrpc":"2.0","id":21,"epoch":"EPOCH-FROM-INITIALIZE","method":"engine.health","params":{"waitMs":1000},"timeoutMs":5000}
{"jsonrpc":"2.0","epoch":"EPOCH-FROM-INITIALIZE","method":"requests.cancel","params":{"id":21}}
```

Cancellation of unknown/already-completed IDs and stale-epoch notifications is
ignored. Notifications never receive replies. EOF cancels outstanding waits,
flushes their responses when possible, and exits. This permits a pipe client to
half-close stdin and still read results. There is no autonomous reconnect or
automatic retry in B03. Kotlin's `RequestTracker` is owned by one background
coroutine in B06; it rejects stale/late/duplicate replies and reports expired or
disconnected IDs for the UI to present as unknown outcomes. No protocol helper
blocks Swing's event-dispatch thread.

| Error code | Meaning |
| --- | --- |
| −32700 | Malformed JSON |
| −32600 | Invalid envelope or frame |
| −32601 | Unimplemented/unknown method |
| −32602 | Invalid params or timeout |
| −32603 | Internal storage/worker failure (details withheld) |
| −32001 | Initialization required |
| −32002 | Incompatible Flow version |
| −32003 | Already initialized |
| −32004 | Missing/stale epoch |
| −32005 | Capacity exceeded |
| −32006 | Reused request ID (connection closes) |
| −32800 | Request cancelled |
| −32801 | Engine deadline exceeded |

Future error codes must be displayed as generic failures, not interpreted as
successful results. A result can be JSON null, so test field presence rather than
truthiness. Exactly one `result` or `error` is required on each response.

## Persistence interfaces for B04

`ProtocolStore` specifies consistent snapshot+high-water reads, ordered replay,
mutation lookup, atomic key/digest claims and transaction-bound outcome recording.
B04's history crate implements it and publishes domain state, events and receipts
atomically with database-wide monotonic event sequences. A replay cursor
contains a persistent store ID plus a decimal-string u64 sequence. It survives
engine restarts; the connection epoch does not. Foreign/pruned cursors require
resynchronization, never an empty page masquerading as success. Replay limits must
be validated and pages bounded; B06's read handlers cap pages at 100 events.

Unknown event kinds keep their payloads intact. Unknown mutation statuses mean
`unknown`, never success. Kotlin retains the original status string. Mutation
idempotency keys bind to a canonical request digest: same key/different digest is
a conflict; same key/same digest returns the existing receipt. A host timeout or
disconnect says nothing about whether a future durable mutation happened. Query
the receipt before retrying; never generate a new key just because transport failed.
Cancellation is not a rollback guarantee. B06 advertises durable read handlers
only when a real history store is open. B08 session commands are not idempotent:
their acknowledgements mean admission, and automatic mutation retries are forbidden.

## Schema and validation

`tests/fixtures/protocol/schema.json` is a schemars-generated catalogue of DTOs,
not a JSON-RPC wrapper. Some semantic limits (opaque IDs, request state, replay
limits) are additionally enforced in code and specified above. Kotlin generation
is not part of schemars: the small hand-written adapters avoid a generator/runtime
dependency and are checked against shared schema fields and golden JSON. Revisit
generation if the model grows substantially.

```sh
cargo run -p protocol --example export_schema --locked -- tests/fixtures/protocol/schema.json
cargo fmt --all --check
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace --all-targets --locked
cd integrations/jetbrains
./gradlew ktlintFormat check buildPlugin verifyPluginProjectConfiguration verifyPlugin \
  --dependency-verification=strict --console=plain
```

Review schema and golden-file changes together. Gradle builds the Rust test engine
and runs Kotlin's golden/schema/correlation tests and a real subprocess handshake,
health/deadline/shutdown test. Fixtures are shared as test resources only. Rust
tests cover framing, invalid inputs, limits, cancellation, EOF and concurrency.

For a small smoke check, pipe just an initialize request to `cargo run -p engine
--locked`; it replies and exits on EOF. For an interactive check, keep stdin open,
copy the returned epoch into health and shutdown requests, and read stdout as
JSON lines. See [B06 bridge behavior](jetbrains-engine-bridge.md) for native launch/disposal/reconnect.

## Implementation validation (2026-10-13; host gate passed 2026-10-14)

- Rust workspace tests: 12 passed, including seven engine transport tests, two
  shared-contract tests, the subprocess smoke test and two ACP diagnostic tests.
- Rust formatting and Clippy with warnings denied: passed.
- Kotlin formatting and standalone compilation with Kotlin 2.2.20 / JDK 21:
  passed. Four JUnit contract tests passed, including the actual Rust subprocess.
- Existing ACP fixture suite: all 17 expected outcomes passed; this run does not
  replace the archived real-adapter evidence.
- Full strict Gradle host command (`check buildPlugin
  verifyPluginProjectConfiguration verifyPlugin`): user reported `BUILD SUCCESSFUL`
  in 1m 4s on 2026-10-14. Local XML reports confirm four protocol tests and three
  native compatibility tests, with no failures, errors or skipped tests. The
  `flow-jetbrains-0.1.0.zip` package was produced.
- Plugin Verifier for IDEA IU-253.33813.55: `Compatible`. It reports four
  deprecated and six experimental API usages in `WorkbenchToolWindowFactory`;
  these are compatibility maintenance items, not failed B03 checks. Local build
  validation is complete; this does not establish a clean-checkout CI result or
  compatibility with other IDE builds.

The validation above records B03's original scope. B04 supplied persistence and
B06 now exposes snapshot/replay and the live Swing connection; see its separate
validation record. No agent task support is advertised yet.
