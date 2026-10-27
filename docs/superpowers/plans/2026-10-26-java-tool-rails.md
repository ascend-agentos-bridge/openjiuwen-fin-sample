# Java Tool Rails Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this small sample inline; no delegation is needed.

**Goal:** Provide a readable Java customization sample with all acceptance assertions in JUnit.

**Architecture:** One source file contains a minimal main, agent/tool setup and two official Rail subclasses. Unit tests exercise decisions and bounded retries; tagged JUnit integration tests call the real model and SDK.

**Tech Stack:** Java 17, Maven, `com.openjiuwen:agent-core-java:0.1.16`, JUnit Jupiter 5.10.2.

**Spec:** `docs/superpowers/specs/2026-10-26-java-tool-rails-design.md`

## Global Constraints

- No framework wrappers or fake model responses.
- No acceptance assertions or test-only instrumentation in main.
- Preserve existing samples and record only real execution output.

## Review Focus

- Reject zero, negative, over-limit, missing and malformed amounts.
- Require approval strictly above 20000; Boolean false must reject.
- Retry BANK_BUSY once and never retry approval interruptions.
- Leave ordinary business errors unretried.
- Real model acceptance must observe a pause before bank execution and success only after approval.

## Task 1: Sample and unit tests

- [x] Add Maven/JUnit configuration, decision and retry tests, and minimal Rail stubs.
- [x] Run `mvn test`; confirm assertions fail for missing validation/retry behavior.
- [x] Implement the two Rails and concise agent/main setup using official APIs.
- [x] Run `mvn test`; require zero failures and errors (23 unit invocations).

## Task 2: Real execution and documentation

- [x] Add tagged real-model JUnit acceptance tests; let the root regression runner invoke Maven directly.
- [x] Run `mvn -Preal-model test` and `mvn compile exec:java` against LM Studio; save actual output.
- [x] Add the sample README and update root README/FEATURES.
- [x] Check the final diff, whitespace, Java main simplicity and sample 23 regression exit code.

## Execution notes

- Initial Rail stubs produced the expected decision/retry failures; the implementation passed those tests.
- Added null/empty arguments as a separate RED→GREEN boundary check.
- Read-only review confirmed the retry/resume structure and reproduced acceptance of JSON with trailing garbage. Added a failing parameterized case and enabled `FAIL_ON_TRAILING_TOKENS`; the full unit suite then passed.
- Removed the redundant Python launchers at the user's request; the regression runner discovers the sample via `pom.xml` and invokes Maven directly. Other samples' concurrent changes are outside this task.

- Final verification: 23 unit cases and 2 real-model cases passed without failures, errors or skips; root sample-23 regression passed; the standalone main launcher produced the recorded four-line output.

- Native Maven entry verification: removed both Python launchers; 3 root-runner unit tests and all 25 sample JUnit cases passed.
