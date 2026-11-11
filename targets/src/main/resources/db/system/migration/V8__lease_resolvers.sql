-- A lease's resolvers (qits-2091). A runner decides a lease container's --dns once, at create: a
-- lease created before the node tunnel or the private edge answered keeps public DNS for its life.
--
-- resolvers: the container's --dns servers in order, as the runner last reported them, a JSON
-- array of strings; empty is the node's own resolvers (or no container yet).
-- resolvers_stale: the runner says the node now has a resolver (the tunnel's 100.96.0.1 or the
-- private edge VIP) that this container lacks first.
-- generation: the owner's refresh counter. Every estate entry carries it, and the runner recreates
-- the lease container (keeping its work volume) when it differs from the container's label.
alter table lease add column resolvers jsonb not null default '[]'::jsonb;
alter table lease add column resolvers_stale boolean not null default false;
alter table lease add column generation bigint not null default 0;
