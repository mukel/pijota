# Release Checklist

Manual checklist for publishing Pijota to Maven Central.

## Before Release

- Confirm `pom.xml` uses a non-SNAPSHOT version.
- Confirm `PijotaVersion` and its test match the release version.
- Confirm the `org.pijota` namespace is verified in Sonatype Central Portal.
- Confirm the `central` server credentials are configured for Maven.
- Confirm the GPG signing key is available and published.
- Confirm `LICENSE` is tracked.
- Confirm no generated or scratch files are present in the repo root.
- Confirm no stale `ai.pijota` references remain (`PackagePathsAreCurrentTest` fails if any do).
- Confirm `CHANGELOG.md` has a section for the version being released.

## Verify

```bash
make check                 # spotless + full test suite
mvn -Prelease -Dgpg.skip package
```

If signing locally, omit `-Dgpg.skip` before deploy.

The smoke tests need provider credentials, so they are always run by hand:

```bash
make native && make native-smoke   # one real turn per provider family + stdin pipe
make acp-smoke                     # editor-protocol client against `pj acp`
```

## Publish

- Create and push a release tag matching the pom version, for example `v0.2.1`.
- Deploy with the `release` profile and Central credentials.
- Choose the intended Central publishing mode in Sonatype Central Portal.
- Wait for Maven Central sync.

## After Sync

```bash
jbang app install --name pj org.pijota:pijota:0.2.1
pj --version
```

For native JBang install:

```bash
jbang app install --native --name pj org.pijota:pijota:0.2.1
pj --version
```
