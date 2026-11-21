# Consumer capabilities and policy

Status: active
Document type: feature-specification
Owner: shared-runtime-consumer-api
Canonical scope: shared-runtime.consumer-api.capabilities-policy
Read when: defining capability discovery, defaults, presets, reasoning support or use-case policy
Last reviewed: 2026-09-08

## Goal

Define the host-owned policy and discovery surface that lets a consumer understand and request useful inference behavior without exposing global Harness state, model-management authority or unrestricted runtime tuning.

This document owns what the host advertises and how requests are authorized/resolved. The Kotlin lifecycle belongs to [`public-surface-v1.md`](public-surface-v1.md). The authority boundary is accepted by [ADR 0013](../../adr/0013-public-consumer-capability-boundary.md).

## Policy model

Capability discovery is always scoped by the authenticated caller and explicit use case:

```text
verified package/signing identity
  -> ApplicationId
  -> UseCaseId
  -> host-owned model binding
  -> use-case readiness
  -> allowed/default presets
  -> reasoning/output/session capabilities
  -> limits and defaults
```

The host must not return a global list and expect the client to filter it.

## V1 model authority

The consumer does not choose a model ID in v1.

Harness owns:

```text
ApplicationId + UseCaseId
  -> AppModelBinding / UseCaseProfile
  -> exact curated model profile
  -> exact artifact digest
  -> installed path
  -> effective runtime profile
```

The capability response may include a privacy-safe effective model label/identity when useful for transparency or compatibility, but it is informational rather than a selector. No path, URL, digest-as-load-command, quantization override or alternate model list is exposed.

If a later product requires client-selectable logical models, that authority change requires a successor ADR before implementation.

## Capability response

The response contains only information needed to decide whether the use case can run and to construct a valid request.

### Identity

- `UseCaseId`;
- capability schema/revision identifier;
- optional human-readable use-case label for reference/demo clients.

### Readiness

Readiness is use-case oriented because model choice is host-owned.

Recommended public states:

| State | Consumer meaning |
| --- | --- |
| `READY` | The use case can be prepared under current policy/state. |
| `AVAILABLE_REQUIRES_PREPARATION` | Authorized and expected to become usable through normal host preparation/load. |
| `UNAVAILABLE_MODEL` | The bound curated model/artifact is not currently usable. |
| `UNAVAILABLE_HOST_POLICY` | Temporarily blocked by host/device policy. |
| `INCOMPATIBLE` | Required protocol/capability is unavailable. |

The response may carry a stable machine-readable detail code, but must not leak install paths, download URLs, native states or host-global inventory.

### Effective model information

When approved for the consumer surface, return only a privacy-safe informational projection such as:

- stable display/product identity;
- concise family/size/quantization label when useful;
- readiness already represented at use-case level.

This field must not be accepted back as authority to load or select a model.

### Presets

For each selectable preset:

- `InferencePresetRef` ID/version;
- display label;
- concise semantic description such as latency/quality/determinism intent;
- whether it is the default;
- high-level capabilities affected when relevant.

A use case may expose one default preset and zero alternatives. The entire raw sampling/runtime configuration is never the normal discovery response.

### Reasoning

Advertise the consumer-visible capability, not an implementation guess:

```text
NOT_SUPPORTED
SUPPORTED_NOT_SURFACED
SURFACED_OPTIONAL
SURFACED_REQUIRED_BY_POLICY
```

`SURFACED_REQUIRED_BY_POLICY` is reserved for a concrete future use case. OMBRA PII uses disabled/not-surfaced reasoning.

### Output constraints

Advertise the supported subset of:

- text;
- JSON;
- JSON schema.

Schema length and transport bounds remain host-defined and use-case scoped.

### Session capabilities

Advertise supported session kinds and bounded message/input limits. Context size remains effective host configuration rather than a normal consumer control.

## Preset semantics

A preset is a versioned behavior contract, not a bag of UI defaults.

A preset can internally own:

- sampling parameters;
- context policy;
- output-token budget;
- thinking/reasoning defaults;
- generation guards;
- runtime tuning profile selection.

The exact internal fields may differ by the bound model while preserving the advertised product intent.

Illustrative product semantics:

| Preset | Intent | Typical trade-off |
| --- | --- | --- |
| `fast` | Minimize latency/resources. | Shorter/lower-cost behavior. |
| `balanced` | Default general-purpose behavior. | Middle ground. |
| `quality` | Favor answer quality. | Higher latency/resource budget. |
| `deterministic` | Stable evaluation/classification behavior. | Less sampling variability. |

Capability discovery is authoritative; these names are not mandatory.

## Preset validation

Preset validity is evaluated against the authenticated application, use case and current host-owned model binding:

```text
verified caller + use case
  -> bound model/profile
  -> allowed preset set
  -> requested/default preset
  -> effective host configuration
```

Rules:

1. omitted preset -> advertised valid default;
2. advertised allowed preset -> resolve exact host configuration;
3. explicit incompatible/not-allowed preset -> typed failure;
4. never silently substitute another preset after explicit selection;
5. never change the model binding to make a requested preset work.

## Default versus recommended

Keep these concepts separate:

- **default** — what Harness uses when the consumer omits a choice;
- **recommended** — an optional UI hint among already allowed choices;
- **available** — what policy and current host state can serve.

A default must be valid whenever the use case is advertised as ready. If no valid default can be resolved, preparation fails explicitly.

## Device-aware policy

Harness may use device capability/tier internally to decide whether the bound model/preset combination is safe or recommended.

The consumer does not reproduce RAM/thermal heuristics. The capability response already reflects host policy.

If conditions change materially, Harness emits a new capability revision or a typed availability change. Existing prepared sessions follow deterministic lifecycle rules rather than silently changing execution identity.

## Reasoning policy

Reasoning support is the intersection of:

```text
bound model capability
AND backend parsing/support
AND use-case policy
AND selected preset/effective mode
AND negotiated protocol feature
```

The consumer must not infer reasoning support from a model label.

An explicit surfaced-reasoning request that cannot be honored fails typed. Harness never exposes hidden/internal reasoning merely because the backend produces it.

## Output policy

Output constraints are use-case scoped.

Examples:

```text
generic-text-generation  -> TEXT
transaction-classify     -> JSON / JSON_SCHEMA
summary                  -> TEXT
document-pii-detection   -> JSON_SCHEMA
```

The real `generic-text-generation` onboarding capability is deliberately TEXT-only. A generic use case does not gain arbitrary JSON-schema execution merely because the backend supports a schema mechanism.

## Limits

Capability discovery may expose stable consumer-relevant bounds such as:

- maximum input characters/messages;
- maximum schema size where supported;
- supported session kinds;
- optional stable output budget categories if introduced later.

Do not expose raw queue size, RAM thresholds, native context allocation or backend batch limits as normal consumer limits.

## Capability revision and caching

The client may cache capabilities only if it can detect staleness.

A stable revision token covers policy-visible changes. A capability response can become stale when:

- host version/policy changes;
- bound-model availability changes;
- app/use-case authorization changes;
- preset/output/reasoning policy changes;
- negotiated protocol features change.

Discovery is advisory, not an authorization token. `prepare` revalidates the caller, use case, revision-sensitive options and current readiness every time.

The client must tolerate `NOT_ALLOWED`, `UNAVAILABLE` or `INCOMPATIBLE` at prepare time after previously successful discovery.

## Host configuration source

Consumer policy is centralized in one host-owned registry rather than scattered across Activities, Binder delegates and UI code.

Conceptually:

```kotlin
ConsumerUseCasePolicy(
    applicationId,
    useCaseId,
    modelBinding,
    readinessPolicy,
    presets,
    defaultPreset,
    reasoningPolicy,
    outputPolicy,
    sessionPolicy,
    limits,
)
```

Existing `AppModelBinding` / resolved use-case configuration must be reused where semantics fit. CA-1 must not create a parallel model-resolution engine.

## Preparation contract

Capability policy is revalidated during preparation:

```text
authenticated caller
  -> resolve authorized use case
  -> resolve current host-owned model binding
  -> validate current readiness
  -> validate requested/default preset
  -> validate reasoning/output/session intent
  -> bind immutable prepared selection
```

An already prepared session does not silently change model or preset when a later capability revision is published.

## Generic onboarding policy

`generic-text-generation` is the bounded host-owned capability used by the runnable Hello Harnex onboarding sample. It exists so an external developer can prove the Consumer SDK/Binder/control-plane/runtime path without copying a product-specific PII workflow.

Its current public semantics are deliberately narrow:

```text
use case: generic-text-generation
model: host-owned reviewed binding (not consumer-selectable)
preset: qwen35-text-quality / Quality
reasoning: disabled / not surfaced
output: TEXT only
session: STATELESS
input messages: 1
maximum input: 12,000 characters
minimum context policy: 4,096 tokens
```

The app must still be explicitly authorized and hold an enabled binding for this use case. Generic onboarding does not create implicit Consumer authority, does not expose model selection and does not make Harnex a general-purpose chatbot. Product consumers should use narrower host-owned use cases when they need stronger domain/output guarantees.

## Reference-consumer policy

The first product-shaped reference consumer is the OMBRA PDF/PII application defined in [`pii-redactor/`](pii-redactor/). Its initial host policy remains deliberately narrow:

```text
use case: document-pii-detection
model: host-owned reviewed binding (not consumer-selectable)
preset: one deterministic host-owned default, no alternatives
reasoning: disabled / not surfaced
output: JSON_SCHEMA required
session: STATELESS
```

Exact artifact support follows reviewed curated catalog/runtime evidence, not application-side selection.

## Authorization and information minimization

Capability discovery is itself an information surface. Therefore:

- authenticate before returning use-case capabilities;
- reject unauthorized use cases without revealing their configuration;
- return only caller/use-case-scoped readiness and allowed options;
- scrub backend/file/path/download/native/private diagnostic data;
- keep signing/package diagnostics out of ordinary success responses;
- do not expose global or other-client inventory;
- bound discovery requests if needed for service robustness.

## Deterministic tests required by CA-1

- authorized caller receives only its requested authorized use-case capability set;
- different callers cannot enumerate each other's use cases or effective model details;
- omitted preset resolves the valid default;
- allowed preset resolves exact expected host policy without changing model binding;
- unavailable bound model produces use-case readiness/failure without fallback/download;
- unauthorized use case fails before model load;
- incompatible preset fails without substitution;
- unsupported reasoning/output request fails typed;
- stale capability selection is revalidated during prepare;
- capability response contains no path/URL/digest-as-command/native/private diagnostic data;
- no public request type contains a model selector in v1.

## Acceptance criteria

CA-1 is complete when:

- one implementation owns caller/use-case capability policy;
- it reuses existing model/use-case resolution rather than duplicating it;
- readiness semantics are deterministic and consumer-actionable;
- defaults and preset compatibility are explicit;
- reasoning/output support is use-case scoped;
- capability caching/staleness behavior is enforced by prepare-time revalidation;
- unauthorized discovery cannot leak global inventory;
- every optional consumer choice can be validated before native generation starts;
- deterministic tests cover default, unavailable, unauthorized, stale and privacy cases.
