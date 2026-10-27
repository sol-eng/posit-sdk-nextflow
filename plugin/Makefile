# Requires a local Gradle install (`GRADLE ?= gradle`); run `gradle wrapper` once to generate
# ./gradlew instead, then override with `make GRADLE=./gradlew ...` or `export GRADLE=./gradlew`.
GRADLE ?= gradle

# Build the plugin
assemble:
	$(GRADLE) assemble

clean:
	rm -rf .nextflow*
	rm -rf work
	rm -rf build
	$(GRADLE) clean

# Run plugin unit tests
test:
	$(GRADLE) test

# Run the real Nextflow with the built plugin against e2e/fake_workbench.py
e2e: assemble
	../e2e/run.sh

# Install the plugin into the local nextflow plugins dir (~/.nextflow/plugins)
install:
	$(GRADLE) install

# Publish the plugin
release:
	$(GRADLE) releasePlugin
