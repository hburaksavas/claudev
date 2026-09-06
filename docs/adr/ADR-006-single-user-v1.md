# ADR-006: Single interactive Windows user, single active app instance (V1)

## Status
Decided.

## Context
Windows machines are occasionally shared across accounts. The domain model needs to decide whether
to design for multi-tenant access to one on-disk store now, or defer that.

## Decision
V1 targets a single interactive Windows user per machine, with a single active application
instance. A second app launch activates the existing instance rather than becoming a second
orchestrator. `Workspace.ownerSid` records the creating user's SID; the app refuses to operate on
workspaces owned by a different SID rather than silently taking control of another user's running
instances.

## Alternatives rejected
- **Design full multi-tenant SQLite locking/sharing now.** Adds real complexity (concurrent-writer
  semantics, per-user scoping throughout the schema) for a scenario (shared machine, multiple
  concurrent users of the same tool) that isn't validated as a real V1 need.

## Consequences
- `owner_sid` is a cheap guard, not a full access-control system: it prevents an accidental
  cross-account collision on a shared machine, it does not implement permissions or sharing.
- If multi-user support is ever needed, this ADR's scope (not the domain model's shape) is what
  changes — `ownerSid` already exists on `Workspace` precisely so that door isn't closed.
