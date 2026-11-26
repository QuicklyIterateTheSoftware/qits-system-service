-- Image-building CI leases are capped per target at the builder gate's width plus one (qits-2568).
-- The runner's qits-ci-daemon gate lets BUILDER_CONCURRENT_BUILDS image builds onto the target's
-- shared buildkitd at once; a CI lease past that only waits at the gate, holding its whole memory
-- charge while the step's clock is stopped. Placement now grants a lease that builds only while
-- fewer than width + 1 such leases occupy the target, so that memory goes to the runs that build
-- nothing.
--
-- target: the gate's width as the runner last reported it (node.builderConcurrentBuilds); null
-- before a runner reported it, or from a runner too old to, and then nothing is capped.
alter table target add column builder_concurrent_builds integer;

-- lease: the owner says this lease will send image builds to the target's builder (qits-ci: the
-- run's pipeline has a `build: true` step). Kept only on a CI lease; false everywhere else.
alter table lease add column builds boolean not null default false;
