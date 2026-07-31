# Vendored web-UI libraries

These are **not** committed. The Maven build downloads them into the classpath
(`target/classes/web/vendor/`) during the `generate-resources` phase via the
`download-maven-plugin` (see `pom.xml`). At runtime `WebServer` serves them from
`/vendor/*`, so the web UI renders **offline / against a local model** with no CDN.

Each download is **pinned** (version in `pom.xml` properties) and **sha512-verified**
(the build fails if the bytes don't match). Downloads are cached after the first
fetch, so subsequent builds work offline.

| file | source | license |
|------|--------|---------|
| `marked.min.js` | `marked` (`${marked.version}`) | MIT |
| `purify.min.js` | `DOMPurify` (`${dompurify.version}`) | Apache-2.0 OR MPL-2.0 |
| `highlight.min.js` | `highlight.js` (`${highlightjs.version}`) | BSD-3-Clause |
| `github-dark.min.css`, `github.min.css` | `highlight.js` themes | BSD-3-Clause |

## Updating a library

1. Bump the version in `pom.xml` (`<marked.version>` / `<dompurify.version>` / `<highlightjs.version>`).
2. Get the new checksum, e.g.:
   ```sh
   curl -fsSL https://cdn.jsdelivr.net/npm/marked@<new>/marked.min.js | openssl dgst -sha512
   ```
3. Replace the matching `<sha512>` in the plugin's `<execution>`.
4. `mvn clean package`. The build re-downloads and verifies. Commit the `pom.xml` diff (version + hash).
