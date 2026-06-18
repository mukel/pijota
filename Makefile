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
#   make native       build GraalVM native image
#   make format       apply code formatting
#   make format-check verify code formatting (read-only)
#   make coverage   run tests and generate JaCoCo coverage report
#   make clean        remove build artifacts

.PHONY: build test test-live check jar run fat native clean format format-check coverage

MVN  ?= mvn
ARGS ?=

# ---- dev loop ----

build:
	@$(MVN) -q compile

test:
	@$(MVN) -q test

# Live, opt-in browser tests (tagged @Tag("live")); needs Chrome and/or geckodriver on the machine.
test-live:
	@PIJOTA_BROWSER_IT=1 $(MVN) -q test -Dgroups=live

check: format-check test

# ---- packaging ----

jar:
	@$(MVN) -q -DskipTests package

fat:
	@$(MVN) -q -DskipTests -Pfat package

native:
	@$(MVN) -q -DskipTests -Pnative package

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
