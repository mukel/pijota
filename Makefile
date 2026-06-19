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
#   make native       build GraalVM native image (size-optimized, portable -march=compatibility)
#   make native-pgo-instrument / native-pgo-build   profile-guided optimized native image (2 phases)
#   make format       apply code formatting
#   make format-check verify code formatting (read-only)
#   make coverage   run tests and generate JaCoCo coverage report
#   make clean        remove build artifacts

.PHONY: build test test-live check jar run fat native native-pgo-instrument native-pgo-build clean format format-check coverage

MVN  ?= mvn
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

# Profile-Guided Optimization (Oracle GraalVM) — typically 10-30% faster/smaller. Two phases:
#   1) make native-pgo-instrument   build an instrumented binary
#   2) run REAL, representative pijota sessions (a coding session, not just --help) → writes default.iprof on exit
#   3) make native-pgo-build         rebuild optimized using the collected profile
# Profile quality drives the gains: the more your profiling run resembles real use, the better.
native-pgo-instrument:
	@$(MVN) -q -DskipTests -Pnative -Dpgo.arg=--pgo-instrument package
	@echo "Instrumented ./target/pijota built. Run representative sessions (it writes default.iprof on exit),"
	@echo "then: make native-pgo-build"

native-pgo-build:
	@test -f default.iprof || { echo "default.iprof not found — run the instrumented binary first (native-pgo-instrument)"; exit 1; }
	@$(MVN) -q -DskipTests -Pnative "-Dpgo.arg=--pgo=$(CURDIR)/default.iprof" package
	@echo "PGO-optimized native image: target/pijota"

# ---- launch ----

target/classpath.txt: pom.xml
	@$(MVN) -q -DincludeScope=runtime -Dmdep.outputFile=$@ dependency:build-classpath

run: build target/classpath.txt
	@java -cp "target/classes:$$(cat target/classpath.txt)" ai.pijota.Main $(ARGS)

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
