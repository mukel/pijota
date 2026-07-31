# Browser-injected scripts

These run inside the real browser via `BrowserSession` (`BrowserBackend.evaluate`).

## Committed (small, in the repo)

| file | purpose | license |
|------|---------|---------|
| `Readability.js` | Mozilla Readability (Firefox Reader View engine) | Apache-2.0 |
| `reader.js` | runs Readability on a clone of the rendered DOM | n/a |
| `extract-page.js` | visible-only DOM walk → markdown (tier 2) | n/a |
| `extract-search.js` | Google results → markdown | n/a |
| `dismiss-consent.js` | best-effort consent/cookie wall dismissal | n/a |
| `extract-pdf.js` | PDF text layer → markdown via PDF.js | n/a |

## Downloaded at build time (**not** committed)

`pdf.min.js` and `pdf.worker.min.js` (PDF.js, **Apache-2.0**) are fetched into the
classpath (`target/classes/browser/`) during the `generate-resources` phase by the
`download-maven-plugin` (see `pom.xml`), exactly like the web-UI libraries in
`web/vendor/`. Both are **version-pinned** (`pdfjs.version` in `pom.xml`) and
**sha512-verified** (the build fails if the bytes don't match) and cached after the
first fetch so rebuilds work offline.

PDF.js is the **legacy UMD build** so that evaluating `pdf.min.js` defines a global
(`globalThis["pdfjs-dist/build/pdf"]`) without an ESM import; `extract-pdf.js` then
builds a Blob worker from the worker source and extracts the text layer.

### Updating PDF.js

Bump `pdfjs.version` in `pom.xml`, then update both `<sha512>` values (the build
prints the expected vs actual on a mismatch, or run `sha512sum` on the downloaded
files under `target/classes/browser/`).
