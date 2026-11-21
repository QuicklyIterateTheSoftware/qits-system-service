-- A lease may avoid a target (qits-2382). qits-ci retries a run whose lease never dialled back, and
-- the allocator's most-free-slots rule sent every retry to the same broken target.
--
-- lease: avoid_target_id, a target the owner would rather not land on. An unpinned lease goes to any
-- other target that can take it, and to this one only when none can; a pinned lease ignores it.
-- Null avoids nothing.
alter table lease add column avoid_target_id uuid;
