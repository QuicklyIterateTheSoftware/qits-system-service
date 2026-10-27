-- Slot minimums are reservations (qits-1682): slots held for one type, not a limit. A type may use
-- more than its reservation while shared (unreserved) slots are free. Only the names change.
alter table target rename column min_ci to reserved_ci;
alter table target rename column min_workspace to reserved_workspace;
alter table target rename column min_desk to reserved_desk;
alter table target rename constraint ck_target_minimums to ck_target_reservations;
