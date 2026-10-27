-- The image each slot type keeps warm on its targets (qits-1692): the newest released version of
-- that type's slot image, taken from qits-ci's SoftwareRelease as soon as it is announced, so a
-- runner pulls it before the first lease that needs it rather than during that lease. One row per
-- slot type, moved forward only; the estate carries `image` to every target that can ever run the
-- type. Kept here rather than in memory so a restart still names it.
create table warm_image (
    slot_type    text primary key check (slot_type in ('CI', 'WORKSPACE', 'DESK')),
    package_name text not null,
    version      text not null,
    image        text not null,
    updated_at   timestamptz not null,
    causation_id uuid
);
