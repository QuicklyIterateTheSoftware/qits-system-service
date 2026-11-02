-- A target reports its OOM kills (qits-1818). The runner's inventory carries the node's memory facts
-- and each lease's: written here as last reported, so an agent sees them without host access.
--
-- target: the node's counters as last reported (`node_oom_kills` its cgroup tree's root
-- memory.events oom_kill, `kernel_oom_kills` /proc/vmstat's oom_kill, the whole physical host's),
-- its memory limit, and when they arrived. A rise of the node counter publishes TargetOomKilled.
alter table target add column node_oom_kills bigint;
alter table target add column kernel_oom_kills bigint;
alter table target add column memory_max_bytes bigint;
alter table target add column memory_reported_at timestamptz;

-- lease: the lease container's cgroup counters (oom_kill, oom), its peak memory, and docker's
-- State.OOMKilled of an exited one. Counters and the peak never move down; a report without them
-- leaves them as they were, so an EXITED lease keeps the last sample its container gave.
alter table lease add column oom_kills bigint;
alter table lease add column oom_events bigint;
alter table lease add column memory_peak_bytes bigint;
alter table lease add column oom_killed boolean;
