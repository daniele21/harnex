# OMBRA vector identity candidates

Status: active
Document type: asset-index
Owner: OMBRA design system
Canonical scope: shared-runtime.consumer-api.pii-redactor.identity
Read when: reviewing or generating OMBRA product identity assets
Last reviewed: 2026-08-15

This directory contains manually recreated vector candidates derived from the approved OMBRA design contract. It intentionally does **not** auto-trace the raster brand board. The document lifecycle is active; the symbol itself remains **REVIEW REQUIRED** and is not approved for production identity use.

## Current candidate

| Asset | Status | Source contract |
| --- | --- | --- |
| [`ombra-symbol.svg`](ombra-symbol.svg) | **REVIEW REQUIRED** | folded document + opaque redaction bar + mint reveal slit |

The candidate uses only the reviewed OMBRA palette tokens from `ux-and-brand.md`. Geometry is deliberately simple and hand-authored so it can be reviewed at vector level and translated deterministically to Android drawables.

## Android generation gate

`scripts/generate_ombra_android_identity.py` owns the deterministic launcher-generation boundary. Its default mode verifies that the production generator remains fail-closed while this index marks the symbol `REVIEW REQUIRED`. `--generate` refuses to write application resources until the symbol row is explicitly changed to `APPROVED`.

The generator is also pinned to the exact reviewed SVG bytes it knows how to translate. A geometry edit therefore blocks production generation until the generator and visual review are deliberately reconciled; approval status alone cannot silently produce stale Android resources.

When the exact supported symbol is approved, the generator is prepared to emit:

- adaptive foreground/background resources;
- Android 13 monochrome resource;
- versioned adaptive-icon XML packaging.

Generation readiness is tooling evidence only. It does not approve the symbol or satisfy final packaging/visual review by itself.

## Not yet approved or generated

The following remain blocked on visual review of the symbol and final typography/wordmark decision:

- `ombra-wordmark.svg`;
- `ombra-lockup.svg`;
- light/dark lockup variants;
- production adaptive launcher output;
- production Android 13 monochrome output;
- app-bar vector;
- final generated mipmap/drawable packaging integration.

Do not use this directory to claim OMB-6 identity completion until the symbol geometry is explicitly reviewed, the remaining masters exist, generation is deterministic and packaging checks pass.
