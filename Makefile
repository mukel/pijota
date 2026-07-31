# Pijota — opinionated terminal coding assistant
#
# Usage:
#   make build        compile only
#   make test         run unit tests (live browser tests self-skip without PIJOTA_BROWSER_IT)
#   make test-live    run ONLY the @Tag("live") browser tests (sets PIJOTA_BROWSER_IT=1; needs Chrome/geckodriver)
#   make check        format-check + test (CI)
#   make run          build and launch (use ARGS="--help" to pass flags)
#   make jar          build thin jar
#   make fat          build fat jar (single uber-jar)
#   make native       build GraalVM native image (portable -march=compatibility; macOS/Linux/Windows, incl. Apple Silicon)
#   make native-pgo-instrument / native-pgo-build   profile-guided optimized native image (2 phases)
#   make setup-musl   [Linux only] one-time: build musl static libs (zlib + stub) for fully static builds
#   make native-static  [Linux only] fully standalone statically-linked native image (musl; needs setup-musl)
#   make format       apply code formatting
#   make format-check verify code formatting (read-only)
#   make coverage   run tests and generate JaCoCo coverage report
#   make clean        remove build artifacts

.PHONY: build test test-live check jar run fat native native-smoke native-pgo-instrument native-pgo-build setup-musl native-static clean format format-check coverage

# Prefer the Maven Daemon (mvnd) when present — its warm JVM makes the dev loop far faster (compile ~4x, tests ~1.3x);
# falls back to plain mvn. Override with `make MVN=mvn ...` to force a fresh, daemonless run.
MVN  ?= $(shell command -v mvnd 2>/dev/null || echo mvn)
# Derive JAVA_HOME from the java on PATH when unset, so mvnd skips its (warned) probe and the build stays quiet.
export JAVA_HOME ?= $(shell realpath "$$(command -v java)" 2>/dev/null | sed 's|/bin/java$$||')
ARGS ?=

# ---- dev loop ----

build:
	@$(MVN) -q compile

test:
	@$(MVN) -q test

# Live, opt-in browser tests (tagged @Tag("live")); needs Chrome and/or geckodriver on the machine.
# fastSettle=false so the real browser gets production settle/consent timings (the fast profile is for FakeChrome).
test-live:
	@PIJOTA_BROWSER_IT=1 $(MVN) -q test -Dgroups=live -Dpijota.browser.fastSettle=false

check: format-check test

# ---- packaging ----

jar:
	@$(MVN) -q -DskipTests package

fat:
	@$(MVN) -q -DskipTests -Pfat package

native:
	@$(MVN) -q -DskipTests -Pnative package

# One turn per provider FAMILY (openai-compatible, anthropic-style, gemini): each family has its own
# native-image reflection surface, so a single-provider test cannot catch a missing-metadata break.
# Families without a configured key are skipped. Needs target/pj (make native).
native-smoke:
	@bash scripts/native-smoke.sh

# Fully standalone (statically linked, no libc dependency) native image via musl + musl-built zlib.
# One-time: `make setup-musl` (needs musl-gcc — e.g. `sudo pacman -S musl` / `apt install musl-tools`).
MUSL_LIBDIR ?= $(HOME)/.local/share/pijota-staticlibs/lib
ZLIB_VERSION ?= 1.3.1

setup-musl:
	@[ "$$(uname -s)" = "Linux" ] || { echo "setup-musl is Linux-only (musl static linking); on macOS/Windows use 'make native'."; exit 1; }
	@command -v musl-gcc >/dev/null 2>&1 || { echo "musl-gcc not found — install musl (pacman -S musl / apt install musl-tools)"; exit 1; }
	@mkdir -p "$(MUSL_LIBDIR)"
	@: > /tmp/pijota-empty.c && gcc -c /tmp/pijota-empty.c -o /tmp/pijota-empty.o && ar rcs "$(MUSL_LIBDIR)/libatomic_asneeded.a" /tmp/pijota-empty.o
	@tmp=$$(mktemp -d) && cd $$tmp && \
		curl -fsSL -o zlib.tgz https://github.com/madler/zlib/releases/download/v$(ZLIB_VERSION)/zlib-$(ZLIB_VERSION).tar.gz && \
		tar xf zlib.tgz && cd zlib-$(ZLIB_VERSION) && \
		CC=musl-gcc LDFLAGS="-L$(MUSL_LIBDIR)" ./configure --static --prefix="$(MUSL_LIBDIR)/.." >/dev/null && \
		$(MAKE) -s && $(MAKE) -s install >/dev/null && rm -rf $$tmp
	@echo "musl static libs ready: $$(ls $(MUSL_LIBDIR))"

native-static:
	@[ "$$(uname -s)" = "Linux" ] || { echo "native-static is Linux-only (GraalVM --static/--libc=musl); on macOS/Windows use 'make native'."; exit 1; }
	@test -f "$(MUSL_LIBDIR)/libz.a" || { echo "run 'make setup-musl' first (no libz.a in $(MUSL_LIBDIR))"; exit 1; }
	@$(MVN) -q -DskipTests -Pnative,static -Dmusl.libdir="$(MUSL_LIBDIR)" package
	@echo "Fully static native image: target/pj ($$(file -b target/pj | grep -o 'statically linked'))"

# Profile-Guided Optimization (Oracle GraalVM) — typically 10-30% faster/smaller. Two phases:
#   1) make native-pgo-instrument   build an instrumented binary
#   2) run REAL, representative pijota sessions (a coding session, not just --help) → writes default.iprof on exit
#   3) make native-pgo-build         rebuild optimized using the collected profile
# Profile quality drives the gains: the more your profiling run resembles real use, the better.
native-pgo-instrument:
	@$(MVN) -q -DskipTests -Pnative -Dpgo.arg=--pgo-instrument package
	@echo "Instrumented ./target/pj built. Run representative sessions (it writes default.iprof on exit),"
	@echo "then: make native-pgo-build"

native-pgo-build:
	@test -f default.iprof || { echo "default.iprof not found — run the instrumented binary first (native-pgo-instrument)"; exit 1; }
	@$(MVN) -q -DskipTests -Pnative "-Dpgo.arg=--pgo=$(CURDIR)/default.iprof" package
	@echo "PGO-optimized native image: target/pj"

# ---- launch ----

target/classpath.txt: pom.xml
	@$(MVN) -q -DincludeScope=runtime -Dmdep.outputFile=$@ dependency:build-classpath

run: build target/classpath.txt
	@java -cp "target/classes:$$(cat target/classpath.txt)" org.pijota.Main $(ARGS)

# ---- code hygiene ----

format:
	@$(MVN) -q spotless:apply

format-check:
	@$(MVN) -q spotless:check

coverage:
	@$(MVN) -q -Pcoverage test jacoco:report
	@echo "Coverage report: target/site/jacoco/index.html"

# ---- cleanup ----

clean:
	@$(MVN) -q clean
