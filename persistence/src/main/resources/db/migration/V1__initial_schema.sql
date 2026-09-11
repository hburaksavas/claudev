-- Initial schema for the workspace/instance/pipeline/operation domain model (docs/DOMAIN_MODEL.md).
--
-- Polymorphic sub-structures (RuntimeSource, RedisSafetyPolicy, the pipeline step list) are stored
-- as JSON text columns rather than normalized into per-variant tables — a pragmatic choice for a
-- single-writer embedded database, not a general recommendation. Repositories for connection,
-- pipeline, and operation are not built yet (see docs/MILESTONES.md WP4/WP7/WP8); their tables
-- exist now so later work is additive, not another schema migration pass discovered late.

CREATE TABLE workspace (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    created_at TEXT NOT NULL,
    owner_sid TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE runtime_definition (
    id TEXT PRIMARY KEY,
    kind TEXT NOT NULL,
    source_json TEXT NOT NULL,
    config_schema_version INTEGER NOT NULL
);

-- instanceIds/pipelineIds on the domain Workspace record are derived by querying these tables by
-- workspace_id, not stored redundantly on the workspace row.
CREATE TABLE instance (
    id TEXT PRIMARY KEY,
    workspace_id TEXT NOT NULL REFERENCES workspace(id),
    runtime_definition_id TEXT NOT NULL REFERENCES runtime_definition(id),
    name TEXT NOT NULL,
    requested_ports TEXT NOT NULL DEFAULT '',
    bound_ports TEXT NOT NULL DEFAULT '',
    data_dir TEXT NOT NULL,
    log_dir TEXT NOT NULL,
    desired_state TEXT NOT NULL,
    observed_state TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_instance_workspace ON instance(workspace_id);

-- One row per instance's *current* launch, if any. A stop deletes the row (or replaces it, for a
-- restart) rather than marking it inactive — "no current launch" is the absence of a row, not an
-- edited one. See docs/PROCESS_SAFETY.md's LaunchRecord identity contract.
CREATE TABLE launch_record (
    instance_id TEXT PRIMARY KEY REFERENCES instance(id),
    pid INTEGER NOT NULL,
    exe_path TEXT NOT NULL,
    exe_fingerprint_sha256 TEXT NOT NULL,
    process_creation_time TEXT NOT NULL,
    instance_token TEXT NOT NULL,
    job_handle_id INTEGER NOT NULL,
    working_dir TEXT NOT NULL,
    command_hash TEXT NOT NULL,
    saved_at TEXT NOT NULL
);

CREATE TABLE connection (
    id TEXT PRIMARY KEY,
    kind_json TEXT NOT NULL,
    environment_class TEXT NOT NULL,
    secret_ref_json TEXT,
    policy_json TEXT NOT NULL
);

CREATE TABLE pipeline (
    id TEXT PRIMARY KEY,
    workspace_id TEXT NOT NULL REFERENCES workspace(id),
    steps_json TEXT NOT NULL
);
CREATE INDEX idx_pipeline_workspace ON pipeline(workspace_id);

CREATE TABLE operation (
    id TEXT PRIMARY KEY,
    kind TEXT NOT NULL,
    targets_json TEXT NOT NULL,
    dag_json TEXT NOT NULL,
    status TEXT NOT NULL,
    started_at TEXT NOT NULL,
    ended_at TEXT,
    cancel_token TEXT NOT NULL
);

-- Append-only per docs/PERSISTENCE.md: no repository in this codebase may expose an update/delete
-- path against this table. The absence of the method is the enforcement.
CREATE TABLE operation_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    operation_id TEXT NOT NULL REFERENCES operation(id),
    node_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    message TEXT NOT NULL,
    timestamp TEXT NOT NULL
);
CREATE INDEX idx_operation_events_operation ON operation_events(operation_id);

-- Append-only, same rule as operation_events.
CREATE TABLE audit_entry (
    id TEXT PRIMARY KEY,
    actor TEXT NOT NULL,
    action TEXT NOT NULL,
    target TEXT NOT NULL,
    timestamp TEXT NOT NULL,
    result TEXT NOT NULL,
    detail TEXT
);
CREATE INDEX idx_audit_entry_timestamp ON audit_entry(timestamp);
