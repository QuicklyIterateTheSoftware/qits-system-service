-- Execution targets (epic qits-1481): this service's first store. qits-system-plan.md records why
-- a service that only read docker now writes, and that only the `targets` module does.

-- A machine a qits-system-runner holds the docker socket of. `slots_total` is what the runner said
-- in its last `hello` (QITS_SYSTEM_RUNNER_SLOTS); the minimums are an operator's reservation per
-- slot type, and SlotAllocator ignores them while their sum is above the total.
create table target (
    id                         uuid primary key,
    name                       text not null,
    description                text,
    slots_total                int not null default 0,
    min_ci                     int not null default 0,
    min_workspace              int not null default 0,
    min_desk                   int not null default 0,
    capabilities               jsonb,
    credential_volume          text,
    login_claude               text check (login_claude in ('PRESENT', 'ABSENT', 'UNKNOWN')),
    login_kimi                 text check (login_kimi in ('PRESENT', 'ABSENT', 'UNKNOWN')),
    login_checked_at           timestamptz,
    registration_token_id      text,
    registration_token_subject text,
    client_id                  text,
    quarantined_at             timestamptz,
    quarantine_reason          text,
    last_health_check_at       timestamptz,
    last_health_check_ok       boolean,
    health                     jsonb,
    runner_version             text,
    registered_at              timestamptz,
    last_seen_at               timestamptz,
    created_at                 timestamptz not null,
    causation_id               uuid,
    constraint uq_target_name unique (name),
    constraint ck_target_minimums check (min_ci >= 0 and min_workspace >= 0 and min_desk >= 0)
);

create unique index uq_target_client_id on target (client_id) where client_id is not null;

-- One owner's claim on one slot. The owner is the calling service's application (owner_app), never
-- a body field; owner_ref is the owner's own key for the work (a run id, a workspace row, a desk).
-- `target_id` is set when the allocator grants it, and set null if the target is deleted after the
-- lease ended (a target holding a live lease cannot be deleted).
create table lease (
    id            uuid primary key,
    slot_type     text not null check (slot_type in ('CI', 'WORKSPACE', 'DESK')),
    owner_app     text not null,
    owner_ref     text not null,
    project_id    uuid,
    work_key      text,
    pin_target_id uuid,
    target_id     uuid references target (id) on delete set null,
    priority      int not null default 0,
    state         text not null
        check (state in ('QUEUED', 'GRANTED', 'RUNNING', 'EXITED', 'FAILED', 'ENDING', 'ENDED')),
    spec          jsonb not null,
    spec_hash     text,
    -- {container, volume}: the retired runner's container to stop and volume to mount, for the
    -- first lease of a row placed by the old registries ("Cutover of running work"). Not in the
    -- dossier's column list; the dossier's estate entry carries it and a lease has to hold it.
    adopt         jsonb,
    exit_code     int,
    detail        text,
    requested_at  timestamptz not null,
    granted_at    timestamptz,
    started_at    timestamptz,
    ended_at      timestamptz,
    causation_id  uuid
);

-- One live lease per owner and owner_ref: a second POST for the same work is 409 LEASE_EXISTS.
create unique index uq_lease_live_owner_ref on lease (owner_app, owner_ref) where state <> 'ENDED';

-- The allocator's queue and the per-target counts.
create index ix_lease_state on lease (state, priority desc, requested_at);
create index ix_lease_target on lease (target_id) where target_id is not null;

-- The volume a lease's /workspace lives on, kept between leases of the same work. A lease with a
-- work_key that already has a row here is pinned to that row's target.
create table work_volume (
    owner_app          text not null,
    work_key           text not null,
    target_id          uuid not null,
    volume_name        text not null,
    created_at         timestamptz not null,
    purge_requested_at timestamptz,
    primary key (owner_app, work_key)
);

create index ix_work_volume_target on work_volume (target_id);
