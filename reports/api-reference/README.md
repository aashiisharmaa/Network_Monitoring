# Backend API reference

Prepared from the current Java controller source on 16 November 2026.

- [Word handbook](Backend_API_Handbook.docx) — editable document.
- [PDF handbook](Backend_API_Handbook.pdf) — shareable reference.
- [Markdown handbook](Backend_API_Handbook.md) — searchable in the IDE.
- [Postman collection](Network_Monitoring.postman_collection.json) — 186 canonical requests grouped by module.
- [Machine-readable inventory](backend-api-inventory.json) — parameters, aliases, examples and source locations/hashes.
- [Coverage validation](documentation-validation.json) — generated artifact and source-coverage checks.

Coverage: **28 controllers, 186 logical operations, 304 method/URL combinations
including aliases**. All 41 JSON-body operations have reviewed request examples.
Response examples are synthetic; partial envelopes are explicitly labelled.
Multipart forms, binary downloads, authentication/license layers, ID meanings,
date formats, pagination, async workflows and deployment differences are documented.

At capture, 147 canonical operations were listed in the running backend's OpenAPI;
39 new native-alarm operations existed in source but were not listed there. An
OpenAPI declaration is not an execution/integration test or proof of migration readiness.

To use the Postman collection, import its JSON file, set `baseUrl` to the actual
backend address, use login to populate `token`, and supply the correct test-resource
IDs in collection variables. Optional query/form parameters are initially disabled.
IDs are not interchangeable between uploads, jobs, snapshots, reports and deliveries.
No requests run merely by generating or importing these documentation files.

## Regeneration

From the repository root, run:

```powershell
python scripts/build_backend_api_documentation.py
```

Requires `python-docx`, `reportlab` and `pypdf` in that Python environment. The
generator reads Java controllers and relevant DTO declarations; examples and
cross-field validation notes are curated in `scripts/backend_api_contracts.py`.
It does not read `.env` or issue business mutations. An optional read-only runtime
OpenAPI capture can be stored at `backend-java/target/api-docs-live.json`; without
it, runtime availability is marked unverified.

New/changed business contracts require reviewing the curated examples and module
notes rather than assuming arbitrary Map bodies have a complete formal schema.
`--inventory-only` writes just the controller inventory without document rendering.
