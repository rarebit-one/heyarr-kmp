# 0001: Generate the wire parsers from the OpenAPI spec, or keep them hand-written?

- **Status:** Proposed. Not yet decided or implemented.
- **Date:** 2026-09-24
- **Scope:** every heyarr REST response parser in `:core`, `:composeApp` and `:androidApp`
- **Related:** `OpenApiContractTest` (rarebit-one/heyarr-kmp#88); heyarr-core ADR-0015 (the OpenAPI
  document is hand-written and contract-tested)

## Context

Every heyarr response in this repo is read by hand. The readers are built on `JsonScan`, a
tolerant field scanner, and this repo uses no serialization library on the wire (see
CLAUDE.md, "Wire JSON is hand-read"). heyarr-core publishes a hand-written OpenAPI 3.1 document
at `api/openapi.yaml`. Its `openapi_test.go` holds the document to the router, but only for
**routes**, not for response fields.

Until #88, nothing checked the field names the parsers read against that document. #88 adds
`OpenApiContractTest`. It extracts the keys each parser reads from source, maps each parser to
its operations, and fails when a read names no field the spec declares. It uses a vendored
copy of the spec, and a weekly job diffs that copy against heyarr-core `main`.

This proposal asks the next question. Should the parsers themselves be **generated** from the
spec, or should they stay hand-written, now that a contract test guards them?

## Measurements

All figures are taken from `main` on 2026-09-24. The scripts are described in #88.

| What | Number | How |
|---|---|---|
| `object …Json` parser objects | 37 | `rg '^(internal \|private )?object \w+Json\b'` |
| Parser declarations held to the spec by #88 | 39 (objects + client classes, per app) | `OpenApiContractTest` output |
| Key reads checked / distinct keys | 398 / 172 | same |
| Key reads in all main sources, including out-of-scope readers | 647 | same |
| `JsonScan.` call sites, main / main+test | 659 / 863 | `rg -c 'JsonScan\.'` |
| Lines in the files that host the mapped parsers (mixed with models and clients) | ~4.2k | `wc -l` |
| Same-named parser pairs duplicated across the two apps | 5 (`WorksJson`, `WorkDetailJson`, `FollowedSourcesJson`, `FollowedItemsJson`, `EnrolClient`) | contract-test scan |
| Semantic duplicates under different names | 3 (`SessionInfoJson`/`SessionJson`, `ContinueJson`/`ContinueClient`, `GroupingJson`/`MusicJson`) | reading |
| Drifts found by #88: **spec gaps** (the server sends a field the spec omits) | 3: `QualityProfile.content_types`, the spaces-changes `cursor` (and `?since`), `PlaybackSource.duration_seconds` | `OpenApiContractTest` allowances |
| Drifts found by #88: **client drifts** (the parser reads a field that is never sent) | 2: `Candidate.size_bytes`, search `poster_url`/`poster` | same |
| Tolerant-read aliases the spec never sends | 69 | `OPENAPI_CONTRACT_VERBOSE=1` |
| Spec size | 9,762 lines of YAML; 115 component schemas; 163 operations; 632 schema properties | heyarr-core `0a45afe` |
| Spec shapes a generator must map | 78 `enum`, 14 `oneOf`, 25 `allOf`, 33 `type: [T, "null"]`, 19 free-form `object`s | walk of the spec |
| Spec churn | 87 commits touch `api/openapi.yaml` in its whole life (2026-08-20 to 2026-09-16, about 4 weeks); 55 add schema properties; **0 remove or rename one**; 626 properties added, 0 removed; +9,932 / -170 lines | `git log` over heyarr-core `main`, diffing the schema property set at each commit |

Two of these numbers matter most for the decision:

1. **The spec is less complete than the server.** Every spec-side drift that #88 found is a
   field the server sends and the spec does not declare. A parser generated from today's spec
   would *drop* `content_types`, `cursor` and `duration_seconds`, which three features read
   today. Nothing in heyarr-core checks response bodies against the schemas.
2. **Churn is additive.** In its first month the spec added about 23 properties per active
   day and removed none. Additive churn cannot break a hand-written tolerant reader. It only
   leaves new fields unread. It would, however, regenerate code, and create review diff, on
   most refreshes.

## Options

### Option 1: generate `JsonScan`-style parsers from the spec

A generator would read `api/openapi.yaml` and emit, for each schema the clients use, a
Kotlin data class plus a reader written on `JsonScan`. That keeps the no-serialization stance:
the generated code calls the same primitives the hand-written code does.

**Build-time codegen vs checked-in generated code.**

| | Build-time (a Gradle task in `build-logic`, output under `build/generated`) | Checked-in (a script writes `…/generated/*.kt`; CI checks that regenerating produces no diff) |
|---|---|---|
| KMP / iOS | Every compile task of `:core` depends on the generator task, including iOS compiles on macOS runners. The generator needs a YAML reader on the *build* classpath, such as SnakeYAML. That is a build-only dependency and never ships. | Plain `commonMain` sources. iOS and Android see ordinary Kotlin. There is no new build wiring. |
| Reviewability | You cannot see the generated diff in a PR. | Every spec refresh shows up as a Kotlin diff. At today's churn, that is most refreshes. |
| Offline / reproducible | The spec must be vendored anyway, because builds cannot fetch. | Same vendored spec. |
| Failure mode | A spec change can break the build of an unrelated PR. | Failures happen at refresh time, in a dedicated PR. |

**Mapping the spec's shapes onto Kotlin.** Each shape needs a decision:

- **Nullability and `required`.** Map `required` to non-null and `type: [T, "null"]` or optional
  to `T?`. The parsers today deliberately default instead: `state ?: "UNKNOWN"`,
  `monitor ?: true`, `title ?: id`. Those defaults are UI policy, not wire facts. They would
  move to a hand-written mapping layer, or be lost.
- **`enum` (78).** A generated Kotlin `enum` fails on the first new server value. That is the
  opposite of today's tolerance: `state` is "kept as sent". A generator would have to emit
  `String` plus known-value constants. That is what the code does today.
- **`oneOf` (14).** Most are `[$ref, null]`, which is easy. The genuinely polymorphic ones, such
  as JSON-RPC `id` and `QualityRule.value`, need a sealed type or a raw slice. `JsonScan` has
  no "raw value" reader today.
- **`allOf` (25).** Mostly `Page` plus items. That is mechanical to flatten.
- **Free-form `object` (19, e.g. `attributes`).** A generator can only emit
  `Map<String, String>` or a raw slice. The keys the apps actually read, such as `artist`,
  `author`, `season` and `episode`, stay hand-written.
- **Envelope aliases and fallbacks.** The 69 aliases exist for older nodes and pre-v1 shapes.
  A generator reads the spec, so it emits exactly one name. Whether that is correct depends on
  whether pre-spec nodes still exist in the fleet. That is a product decision, not a codegen
  one.

**What codegen buys.** One source of truth. The 5 + 3 duplicated parsers collapse by
construction. A new field is a refresh away.

**What it costs.**
- A generator to write and own. No off-the-shelf generator targets `JsonScan`:
  openapi-generator's `kotlin` / `multiplatform` targets emit kotlinx-serialization + Ktor,
  which violates the stance.
- A second, hand-written layer for defaults, enums-as-strings, free-form attributes and
  derived values such as `downloadProgress`.
- Codegen is **blocked on heyarr-core making the spec complete**. Without response-body
  checks there, generation silently removes fields the apps use today.

### Option 2: keep hand-written parsers, guarded by the #88 contract test and drift job

Keep the parsers as they are. #88 already turns the dangerous direction into a failing test:
the parser reads a name the spec does not have, so it reads null forever. The allowances make
every exception visible, with a reason, and the stale-allowance check removes them once
fixed. The weekly `upstream` job warns when the vendored spec falls behind, and fails when
heyarr-core `main` breaks a parser.

**What it buys.**
- No generator.
- No new build wiring on any target, including iOS.
- No generated diffs.
- The parsers keep their tolerance and their UI defaults in one place.

**What it costs.**
- Duplication stays, and needs converging into `:core` by hand. CLAUDE.md already asks for that.
- New fields are adopted by hand.
- The check is name-level. It cannot tell *which* object a name was read on, and it does not
  check types. An `intField` on a string field reads null. So a field that moved between
  nested objects under the same name passes.
- Keys held in variables are not extracted, for example `ATTRIBUTE_KEYS.map { k -> … }`.

## Recommendation

**Option 2 now. Revisit Option 1, as checked-in generation of data classes and readers only,
if and when the spec is proven complete.**

- The measured churn is purely additive. That is the change a tolerant hand-written reader
  already survives, and the change that makes codegen the noisiest.
- The spec is *less* complete than the server: 3 of 3 spec-side drifts are omissions. Codegen
  today would regress three features. The contract test instead records those gaps as
  allowances to fix upstream.
- The real problem is the parser **duplication** (5 + 3). It is cheaper to fix by converging
  on `:core`, which is already the stated direction, than by building a generator to get
  convergence as a side effect.

Low-cost follow-ups that make the Option 1 decision easier later:

1. heyarr-core: fix the three spec gaps. Consider a response-body schema check (for example,
   validate handler test responses against `openapi.yaml`) so that the spec is provably
   complete.
2. heyarr-kmp: delete the two client-drift reads, and prune dead aliases as parsers are
   touched.
3. heyarr-kmp: converge the duplicated parsers into `:core`. The contract test's mapping
   table then shrinks to one entry per endpoint.
4. Optionally, add type checks to the contract test: `intField` against `integer`,
   `stringField` against `string`. The spec already has the types, and the extractor already
   knows which reader was called.

**Revisit trigger:** heyarr-core validates response bodies against the spec, **and** the
duplicated parsers still exist, or a third client (for example iOS) needs the same readers.

## Migration sketch (only if Option 1 is chosen later)

1. **Generator:** a JVM script under `build-logic/` or `scripts/`, run at refresh time. It
   reads the vendored JSON spec with the same minimal reader the contract test uses, so it
   needs no YAML dependency. It emits `core/src/commonMain/kotlin/…/heyarr/generated/`: one
   `data class` and one `JsonScan` reader per schema **reachable from an allowlisted set of
   operations**, so unused schemas generate nothing. Rules:
   - `required` and non-null become non-null.
   - Everything else is `T?`.
   - `enum` becomes `String` plus a `Known` constants object.
   - `oneOf [$ref, null]` becomes nullable; other `oneOf` becomes a raw JSON slice.
   - A free-form object becomes a raw slice with a `stringMap` accessor.
2. **Checked in, with a no-diff CI check:** CI runs the generator and fails on a diff. The
   `upstream` job from #88 regenerates as part of a refresh PR.
3. **Per endpoint, one PR each:** point the `HeyarrApi` method at the generated reader. Keep a
   thin hand-written mapper for UI defaults and derived values. Delete the old parser and its
   app-side twin. The contract test mapping entry for that endpoint goes away, because
   generated readers are correct by construction.
4. **Done when:** no hand-written parser reads a heyarr REST response. `JsonScan` stays for MCP
   tool results (no outputSchema), local files and third-party APIs.

Rough cost: generator plus rules, about 1–2 weeks. Migration, about 30 endpoint PRs.
heyarr-core first needs its spec-completeness check.
