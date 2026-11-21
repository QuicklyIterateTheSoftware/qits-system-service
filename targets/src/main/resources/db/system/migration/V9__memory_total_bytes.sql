-- A target reports its physical memory (qits-2362). The runner's inventory carries the node's
-- MemTotal from /proc/meminfo next to its root memory.max, so a node whose root cgroup has no limit
-- (`max`: localhost, a workstation) is still sized for placement.
--
-- target: MemTotal in bytes as last reported; null before a runner reported it, or when unreadable.
alter table target add column memory_total_bytes bigint;
