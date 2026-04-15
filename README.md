# MHI3 Audi Update Package Version Manager

Java 17 + JavaFX + Maven desktop application for scanning a selected folder and safely updating MHI3-style `.mnf` and `.mnf.cks` packages to a target version (e.g., `P4368`).

## Features
- Recursive folder scan with full file index (name, relative path, extension, size).
- Structured JSON parsing via Jackson for `.mnf` and `.mnf.cks`.
- Version model derivation:
  - target input: `P4368`
  - numeric MUVersion: `4368`
  - wildcard train version: `P436*`
- `SupportedTrains` strategies:
  - replace latest wildcard entry
  - append wildcard if missing
  - normalize wildcard replacements
- Checksum algorithm support: SHA1, SHA-256, MD5, CRC32.
- Checksum target resolution classification:
  - EXACT, DERIVED, HEURISTIC, UNRESOLVED
- Manual mapping for unresolved checksum entries (session-local).
- Preview mode, backup mode, restore mode, and atomic file writes.
- Cancel/Stop support for scan, preview, apply, and report export tasks.
- Report export as CSV, TXT, HTML.
- Remembers last selected root folder.

## Build & Run
### Online build
```bash
./mvnw -U clean test
./mvnw -PrunGui -DrunGui=true javafx:run
```

### Package shaded JAR
```bash
./mvnw -Ddist=true -Pdist clean package
java -jar target/mhi3-version-updater-1.1.0.jar
```

### Prefetch dependencies for restricted environments
```bash
./mvnw -U -DskipTests dependency:go-offline
```
Then build offline:
```bash
./mvnw -o clean test
```

## Backup & Restore workflow
- Backups are created per apply operation and grouped into a backup session.
- Session metadata is persisted under:
  - `.mhi3-backups/metadata/<session-id>.json`
- Restore options in GUI:
  - restore selected file
  - restore all files from last run
  - restore all files from selected backup session

## GUI Overview
- Inputs: root folder, target version, derived MU/wildcard labels, update/safety checkboxes.
- Outputs:
  - folder tree
  - matched files table
  - old/new JSON field preview table
  - checksum resolution table (target/file/type/confidence/action)
  - unresolved logs
  - progress bar
  - summary panel
- Buttons: Scan, Preview Changes, Apply Changes, Restore Backup, Export Report, Stop.

## Sample Data
Located in `src/test/resources/sample-data/`:
- `main.mnf`
- `main.mnf.cks`

## Project Structure
- `ui` – JavaFX app/controller
- `model` – DTOs/settings
- `scanner` – recursive scanner and file index
- `parser` – JSON parsers for `.mnf` and `.mnf.cks`
- `updater` – update coordinator workflow
- `checksum` – checksum calc + resolver
- `backup` – backup/restore + backup metadata history
- `report` – CSV/TXT/HTML report generation
- `operation` – cancellation and operation context
- `util` – version transformation helpers

## Troubleshooting
### Maven Central 403 / blocked network
- Corporate proxies/firewalls can return HTTP 403 for Maven Central.
- Try:
  - adding proxy config to `~/.m2/settings.xml`
  - using a corporate artifact mirror
  - running `dependency:go-offline` on an allowed network, then building with `-o`

### Missing local repository artifacts
- Clear only problematic artifacts and retry with `-U`:
```bash
rm -rf ~/.m2/repository/org/openjfx
./mvnw -U clean test
```

### JavaFX runtime issues
- If UI fails to launch, verify Java 17+ and platform JavaFX binaries are resolved.
- For headless/CI environments, run only non-UI tests.

### Running shaded jar vs javafx:run
- `javafx:run` is ideal for development/debugging.
- Shaded JAR is ideal for distribution.

## Notes
- JSON files are parsed/updated structurally; no blind global replacement.
- Unknown/binary content handling can be extended with additional parsers.
