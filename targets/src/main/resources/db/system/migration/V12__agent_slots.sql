-- The AGENT slot type (qits-2519): qits-agents' coding agents, leased like a desk — a signed-in
-- target, the credential volume, a kept work volume `agent-<agentId>`. A fourth type beside CI,
-- WORKSPACE and DESK, with its own reservation and maximum, spelled as the desk's are (V3, V5).
--
-- target: reserved_agent, the slots held for AGENT (0 until an operator reserves some); max_agent,
-- its optional ceiling (null is none). Both checks are widened to cover them.
alter table target add column reserved_agent int not null default 0;
alter table target add column max_agent int;
alter table target drop constraint ck_target_reservations;
alter table target add constraint ck_target_reservations check (
    reserved_ci >= 0 and reserved_workspace >= 0 and reserved_desk >= 0 and reserved_agent >= 0);
alter table target drop constraint ck_target_maximums;
alter table target add constraint ck_target_maximums check (
    (max_ci is null or max_ci >= 0)
    and (max_workspace is null or max_workspace >= 0)
    and (max_desk is null or max_desk >= 0)
    and (max_agent is null or max_agent >= 0));

-- lease and warm_image: AGENT is a slot type their checks admit.
alter table lease drop constraint lease_slot_type_check;
alter table lease add constraint lease_slot_type_check
    check (slot_type in ('CI', 'WORKSPACE', 'DESK', 'AGENT'));
alter table warm_image drop constraint warm_image_slot_type_check;
alter table warm_image add constraint warm_image_slot_type_check
    check (slot_type in ('CI', 'WORKSPACE', 'DESK', 'AGENT'));
