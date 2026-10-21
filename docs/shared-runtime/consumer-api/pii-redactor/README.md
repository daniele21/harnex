# OMBRA PII redactor consumer plan

Status: active
Document type: feature-index
Owner: apps/local-llm-console
Canonical scope: shared-runtime.consumer-api.pii-redactor.routing
Read when: locating the target, architecture, UX, detection pipeline or delivery plan for the first PII Consumer API application
Last reviewed: 2026-08-15

This is the progressive-disclosure entry point for turning `apps/local-llm-console` into **OMBRA**, a minimal product-shaped application that uses Local LLM Harness to find user-selected PII in a text-bearing PDF, supports human review and exports a newly generated anonymized PDF.

The generic application-facing inference contract remains owned by the parent [`../`](../) plan. This directory owns only the reference consumer's product and implementation requirements. Integrated behavior and repository priority remain in [`../../../current-state.md`](../../../current-state.md); no milestone in this plan is implemented merely because the design documents and visual boards exist.

## Product rule

OMBRA owns the document workflow and PII policy. Harness owns authenticated, bounded local inference.

```text
user-selected PDF
  -> consumer extracts and segments text locally
  -> user selects built-in and custom PII definitions
  -> consumer builds a bounded, injection-resistant analysis request
  -> Harness executes document-pii-detection with JSON_SCHEMA output
  -> consumer validates findings against the source segments
  -> user accepts, ignores or reveals candidates in memory
  -> consumer writes a new PDF containing irreversible placeholders
```

## Read only what you need

| Question | Canonical source |
| --- | --- |
| What is the user outcome, MVP scope and responsibility split? | [`target.md`](target.md) |
| Where do document, PII, inference, redaction and UI responsibilities live? | [`architecture.md`](architecture.md) |
| Which OMB-0 decisions/spikes are accepted or still open? | [`omb0-decisions-and-spikes.md`](omb0-decisions-and-spikes.md) |
| How are definitions, prompt input, chunking, JSON results and export handled? | [`detection-and-redaction.md`](detection-and-redaction.md) |
| How are the OMBRA brand kit and six mockup views implemented? | [`ux-and-brand.md`](ux-and-brand.md) |
| Where is the review-gated OMBRA vector identity candidate tracked? | [`../../../assets/consumer/ombra/master/README.md`](../../../assets/consumer/ombra/master/README.md) |
| Which automated, quality, privacy and physical checks are required? | [`validation-and-rollout.md`](validation-and-rollout.md) |
| Which frozen semantic-quality thresholds decide supported-model claims? | [`quality-policy-v1.md`](quality-policy-v1.md) |
| In what order should implementation land and what closes each milestone? | [`roadmap.md`](roadmap.md) |
| Where are the generated visual references? | [`../assets/README.md`](../assets/README.md) |

Do not read every source for a focused change. A PDF parser change should read the OMB-0 decision record, document pipeline and validation sources. A Compose styling change should read the UX/brand source and the repository design-system contract. A Binder change should start from the parent Consumer API workstream, not this application plan.

## Naming and ownership

- **OMBRA** is the user-facing brand of the reference consumer; it does not replace the Harness host identity.
- `apps/local-llm-console` remains the first implementation owner so the plan satisfies CA-5 without adding a second application or duplicated Binder composition.
- Package, module and signing identity remain unchanged until a separate migration decision proves a rename is required.
- `document-pii-detection` is the host-authorized `UseCaseId` target for OMBRA.
- `InferencePreset` means a Harness-owned execution behavior; `PiiDefinitionSet` means consumer-owned detection categories. They are never the same abstraction.

## Ownership map

| Boundary | Owner |
| --- | --- |
| Caller authentication, model resolution, generation and metrics | Harness and the parent Consumer API |
| PDF selection, extraction, normalization and segmentation | OMBRA document boundary |
| Built-in/custom PII definitions and prompt payload | OMBRA PII/analysis boundary |
| JSON-schema request and Consumer API lifecycle | OMBRA inference adapter plus packaged client SDK |
| Finding validation, deduplication and placeholder plan | OMBRA redaction boundary |
| Review, reveal and export experience | OMBRA presentation boundary |
| OMBRA tokens and reusable Compose primitives | `ui/design-system` without changing `HarnessTheme` |

## Fixed first-slice constraints

- Text-bearing PDFs only; OCR and scanned-image recognition are deferred.
- One foreground, user-visible analysis with explicit cancellation.
- Consumer capability limits determine usable prompt/schema size; the app does not treat protocol maxima as target document sizes.
- Analysis is sequential and stateless per chunk under the one-active-decode default.
- Reasoning is disabled/not surfaced; final output is a fixed JSON-schema projection.
- Model findings are candidates until the consumer validates source membership and the user reviews them.
- Export creates a new flattened PDF; drawing opaque boxes over recoverable original text is not redaction.
- Prompt, extracted text, findings and reveal mappings stay out of normal telemetry, saved state and evidence.

## Relationship to the Consumer API roadmap

The PII workflow is the concrete CA-5 reference application. CA-0 through CA-4 are now integrated; OMB-0 is the active gate before OMBRA domain/product implementation grows. This plan must not add PII-specific AIDL methods or bypass capability discovery to move faster.

## Visual direction

The generated boards are linked from the [visual asset index](../assets/README.md). They establish layout, hierarchy and brand direction. The implementation source of truth becomes reviewed code tokens, vector masters, semantics and screenshot tests as each milestone lands.

## State rule

Milestone state is owned only by [`roadmap.md`](roadmap.md). `PLANNED` behavior must not be described as available. Physical-device, model-quality or privacy claims remain pending until the exact app/host/model identity passes the validation plan.
