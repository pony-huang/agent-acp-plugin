# Debugging Workflow Guide

This document captures a practical workflow for debugging problems in this repository.

It started from ACP streaming/rendering troubleshooting, but the method is intentionally general:

- form layered hypotheses
- add focused logs at boundaries
- reproduce once
- eliminate possibilities with evidence
- fix only after the failing layer is identified

## When to use this guide

Use this process when the user reports symptoms like:

- behavior does not match logs or expectations
- data seems to arrive but the final result is wrong
- state appears correct in one layer and wrong in another
- the cause is unclear and multiple plausible explanations exist
- a bug could live in transport, service state, UI, persistence, or integration code

## Core principle

Do not jump directly to a fix. Start by splitting the problem into layers, add focused logs, and use the logs to eliminate possibilities.

The preferred order is:

1. State the most likely layers where the bug could live.
2. Add temporary or durable logs at the boundary between those layers.
3. Reproduce once and compare the same entity across layers using stable identifiers.
4. Fix only after the failing layer is identified.
5. Add a regression test at or near the layer that failed.

## Recommended hypothesis ladder

For most bugs, evaluate possibilities in this order:

1. The triggering event never happened.
2. The event happened, but it was dropped, transformed incorrectly, or overwritten.
3. Internal state is correct, but the next layer consumed stale or incomplete data.
4. The next layer received correct data, but presentation/filtering/selection logic hid or distorted it.
5. The result is logically correct, but timing, layout, caching, or lifecycle behavior makes it appear wrong.

This ordering is useful because each step narrows the problem surface significantly.

## Choose the layers first

Before adding logs, explicitly name the layers involved.

Typical layer boundaries in this repository include:

- external tool / ACP agent -> client bridge
- client bridge -> `AcpSessionService`
- service state mutation -> flow/state publication
- flow/state publication -> tool window / panel render state
- render state -> Swing component tree
- user action -> command handler -> resulting state mutation

For other bugs, the same idea still applies:

- input -> parser
- parser -> domain state
- domain state -> persistence
- persistence -> reload path
- backend result -> UI adapter -> rendered component

## Logging strategy

Add logs at the narrowest useful boundaries.

### 1. Event ingestion

Log the raw update, callback, or command result when it first enters the repository code.

This proves the upstream side actually emitted what you think it emitted.

### 2. Post-mutation state

After applying the update, log the mutated state in compact form:

- stable id
- counts
- lengths
- status
- selected keys or flags

This distinguishes “event arrived” from “state was mutated correctly”.

### 3. Downstream snapshot

At the next layer boundary, log the snapshot that layer actually received.

If this snapshot is already wrong, the bug is upstream.
If this snapshot is correct, the bug is downstream.

## Compare by stable identifiers

When reviewing logs, compare the same entity across layers by stable identifiers such as:

- message id
- session id
- tool call id
- request id
- file path
- content length
- entry count
- status value

Do not rely only on textual snippets. Stable identifiers and compact metrics make mismatches easier to spot.

## Common failure patterns

### 1. Lost updates from non-atomic state writes

If multiple paths read state, transform it, and assign a new copy, concurrent updates can overwrite each other.

Preferred fix:

- use atomic state-update helpers where available, such as `MutableStateFlow.update { ... }`

Avoid read-transform-write patterns on shared mutable state when updates can interleave.

### 2. Correct state, wrong behavior because of filtering rules

A downstream layer can receive correct data and still hide it because of filtering, selection, or presentation rules.

Typical examples:

- message content hidden because a tool call of a certain kind exists
- valid state excluded by a “show only latest” rule
- an item present in state but omitted by a rendering filter

When logs show correct downstream snapshots, inspect filters before changing upstream logic.

### 3. Logic from one layer leaking into another

A rule that belongs in a narrow component or adapter can incorrectly affect the whole feature.

General rule:

- keep narrow presentation rules local
- avoid message-level, session-level, or global filtering unless the behavior is truly global

### 4. Correct logic, misleading symptom due to timing or lifecycle

The state may be correct, but asynchronous scheduling, lifecycle transitions, layout timing, or cache invalidation can make it look wrong.

Suspect this when:

- logs show correct final state
- repeated reproduction is timing-sensitive
- refreshing, reopening, or resizing changes the symptom

## How to decide the failing layer from logs

### Case A: expected event missing at ingress

Suspect:

- upstream tool or agent behavior
- subscription timing
- callback registration
- integration wiring

### Case B: ingress is correct, but post-mutation state is wrong

Suspect:

- mutation logic
- stale overwrite
- wrong target selection
- merge/update bugs

### Case C: post-mutation state is correct, but downstream snapshot is wrong

Suspect:

- state propagation
- flow combination
- derived-state mapping
- stale reads

### Case D: downstream snapshot is correct, but user-visible result is wrong

Suspect:

- filtering
- rendering rules
- layout
- lifecycle
- visibility toggles

## Testing guidance

After the failing layer is identified, add a regression test close to that layer.

Examples:

- state mutation test for dropped or overwritten updates
- adapter test for incorrect mapping/filtering
- UI test for visibility/layout regressions
- integration test for boundary contract failures

Prefer focused tests that encode the specific failure chain, rather than broad end-to-end tests by default.

## Repository-specific example

One confirmed bug in this repository followed this pattern:

1. Logs showed final assistant content arrived.
2. `AcpSessionService` logs showed `contentLength` growing correctly.
3. `ChatViewPanel` logs showed the final assistant snapshot was also correct.
4. The actual problem was a message-level filter in `MessageCardPanel` that hid all assistant content whenever the message contained an `edit` tool call.

That bug was solved by fixing the filtering layer, not the transport or session layer.

This is a good example of why layered elimination is preferable to speculative fixes.

## Repository-specific checklist

Before concluding a fix, check:

1. Have the candidate layers been named explicitly?
2. Is there at least one log at each boundary needed to eliminate possibilities?
3. Are you comparing the same entity across layers with stable identifiers?
4. Have you ruled out state overwrite on shared mutable state?
5. Have you checked whether the bug is actually caused by filtering or presentation logic?
6. Is there a regression test for the exact symptom?

## Short version

Use this sequence:

1. Form layer-based hypotheses.
2. Add boundary logs.
3. Reproduce once.
4. Compare the same entity across layers with stable identifiers.
5. Fix the first layer that diverges.
6. Add a regression test.

That process is the preferred default for debugging non-trivial issues in this repository.
