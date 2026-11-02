-- Slot maximums (qits-1813): an optional ceiling per type, next to the reservations. Null is no
-- ceiling, which is every target's value until an operator sets one. A reservation holds slots
-- for its type and is not a limit; a maximum is the limit: a type at its maximum is granted
-- nothing more on that target, however many shared slots are free. The door refuses a maximum
-- below its type's reservation (MAXIMUM_BELOW_RESERVATION); one above the total is harmless.
alter table target add column max_ci int;
alter table target add column max_workspace int;
alter table target add column max_desk int;
alter table target add constraint ck_target_maximums check (
    (max_ci is null or max_ci >= 0)
    and (max_workspace is null or max_workspace >= 0)
    and (max_desk is null or max_desk >= 0));
