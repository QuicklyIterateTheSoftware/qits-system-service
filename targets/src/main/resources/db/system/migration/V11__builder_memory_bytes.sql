-- A target reports its builder's memory cap (qits-2503). The runner starts the target's shared
-- buildkitd under --memory = BUILDER_CONCURRENT_BUILDS × 6 GiB and reports that cap in its inventory
-- next to MemTotal and the root memory.max. Image builds a CI lease sends to the builder run in its
-- cgroup, outside the lease's own limit, so placement charges the cap on a target that takes CI.
--
-- target: the builder's memory cap in bytes as last reported; null before a runner reported it, or
-- from a runner too old to report it.
alter table target add column builder_memory_bytes bigint;
