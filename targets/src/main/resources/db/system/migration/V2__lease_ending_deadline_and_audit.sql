-- Operators end any lease, ENDING gets a deadline, and both leave a trail (epic qits-1621, the
-- dossier page "CI cancel and lease end").

-- When the lease last went ENDING, and when LeaseEndingDeadline re-sent its target's estate because
-- the runner had not reported the container gone by `ending_since + ending-deadline`. Past
-- `ending_nudged_at + ending-grace` it is ENDED without the runner's word.
alter table lease add column ending_since timestamptz;
alter table lease add column ending_nudged_at timestamptz;

-- A lease already ENDING has no recorded entry; its deadline counts from this migration.
update lease set ending_since = now() where state = 'ENDING';

-- Who did what to a target or a lease, beyond what its owner does: an operator's lease end, the
-- ENDING deadline, and (qits-1623) the Docker API's operations. Written in its own transaction, so
-- a refused or failed call is recorded too.
create table target_audit (
  id uuid primary key,
  at timestamptz not null,
  actor text not null,          -- principal (token subject) or 'qits-system'
  roles text not null,          -- the caller's qits:* groups, comma-joined
  target_id uuid,               -- null for a lease with no target
  lease_id uuid,
  operation text not null,      -- LEASE_END, LEASE_ENDING_DEADLINE, DOCKER_<OP>
  resource text,
  args jsonb,
  outcome text not null,
  detail text
);
create index target_audit_target_at on target_audit (target_id, at desc);
