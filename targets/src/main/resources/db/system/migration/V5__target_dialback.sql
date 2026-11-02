-- A target whose CI leases never dial back is fed no more of them (qits-1809). qits-ci ends a lease
-- whose ci daemon never registered with `?reason=never-dialled-back`; such an end of a RUNNING CI
-- lease counts one more in a row, any other end of one resets the count to 0 (its daemon dialled,
-- so the link worked). At `qits.system.targets.dialback.threshold` the target is quarantined, and the
-- `dialback` health check keeps it there until `dialback_failed_at + dialback.cooloff` has passed.
-- A greenlight, by a passing check or an operator, resets the count.
alter table target add column dialback_failures integer not null default 0;
alter table target add column dialback_failed_at timestamptz;
