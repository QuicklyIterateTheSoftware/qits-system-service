# qits-platform-system

The base system panels: the machine the platform runs on, the swarm it forms, and a terminal into
either. It exists so that looking at the host stops meaning `ssh`.

One PLATFORM service — one instance per machine, not one per environment — serving its own Angular
client at `/` on its own host, `system.<env>.<domain>`, with its API at `/system/api` and its health
at `/system/q/health/ready`. The machine surface keeps its segment and is path-routed on every host,
so the client's same-origin calls and its terminal socket dial are unchanged by the move.

## What it does

| Question | Where the answer comes from |
|---|---|
| what is this machine | `docker info`, `docker system df` |
| what is this swarm | `docker node ls/inspect`, `service ls/inspect/ps`, `config ls/inspect`, `secret ls`/`inspect --format` (+ `service ls -q`/`inspect --format` for usage) |
| what is on this node | `docker ps -a`, `container inspect`, `logs --tail`, `image ls`, `volume ls`, `network ls` |
| a live view of the host | a `glances` container on a pseudo-terminal this service owns |
| a shell in a container | `docker exec -it <id> <shell>` on the same kind of terminal |

Every answer about the host is read from the docker daemon at the moment it is asked: no cache and
no schema for those, and a restart loses nothing of them except the live terminals, which it is
meant to lose. The one store is the execution targets' (below).

## What it does NOT do

- **No writes — except the `targets` module's.** No scale, no restart, no rm, no config or secret
  create: the docker surface is read plus a shell. The one exception, recorded in the wrapper's
  `qits-system-plan.md` ("Execution targets (qits-1481)"), is the targets module's own rows and the
  control it sends its runners (see "Execution targets").
- **No secret values.** Swarm does not return them to anybody, including a manager. Config data IS
  shown, decoded — that is what a config is for. A secret's row names the services that use it
  (`usedBy`, qits-2350), read from the service specs with a `--format` template printing only each
  service's name and secret references. The secret and config list rows are one `secret inspect` /
  `config inspect --format` over the ids `ls` printed (qits-2487), so their `createdAt`/`updatedAt`
  are RFC 3339 instants rather than the `ls` formatter's "2 months ago"; a secret's document holds
  no value to print, and the config row template never asks for a config's data.
- **No environment values.** A service's and a container's environment is shown as KEYS only. That
  is where a platform service's injected database password and idp client secret live.
- **One node.** The node this service runs on. A node-scoped read for any other answers
  `409 {"code":"NODE_REMOTE"}` rather than an empty list, because "nothing is there" and "I cannot
  see over there" are different facts. Swarm-level reads are cluster-wide, because a manager
  genuinely knows them.

## The socket

**This service holds the host's docker socket** — the third holder on the platform, after
qits-containers and qits-deployments. It is a deliberate, recorded grant in qits-bootstrap-cli's
extras block for this app:

    mounts[0]=bind:/var/run/docker.sock:/var/run/docker.sock
    groups[0]=${DOCKER_GID}
    mounts[1]=volume:qits-platform-system-config:/work/config
    env.DOCKER_CONFIG=/work/config

Both halves of the first grant are needed: the image runs as uid 1001 and the socket is
`root:docker 0660`, so without the group the CLI is present and every call is a permission denial.
The config volume is where the CLI writes and where the registry + mirror credential lives — the
edge grants no anonymous reads, so the glances pull needs one.

**The credential is moving to this service's own idp client (qits-879).** `idp:client` in
`.config/qits/deployments.yml` has qits-deployments provision `dev-qits-system` and inject
`QITS_RESOURCE_IDP_CLIENT_ID`/`_SECRET`; at boot `startup/DockerCredentialWriter` writes
`/tmp/qits-docker/config.json` (0600) with a Basic entry for the mirror host of
`glances.image-repo` and its `registry.` sibling, before the boot sweep pulls glances. Docker
children get `DOCKER_CONFIG=/tmp/qits-docker` only when that file exists, so the mounted
`/work/config` above keeps working for an older spec or a rollback until it is retired.

The same `idp:client` resource also backs a second consumer: the named OIDC client `qits`
(`quarkus.oidc-client.qits` in `application.properties`, qits-428), which mints this service's own
`qits:system` bearer by client credentials for whatever here reads it by name through
`OidcClients` — qits-eventstream's calls to qits-events first, its telemetry later — off under
`%dev` and `%test` and pacted against qits-idp (`IdpConsumerPactTest`'s `MINT` row, run through the
real client by `IdpTokenMintPactTest`).

With no socket the service still starts, serves its client, and answers 503 on every read. That is
the honest behaviour: an unreachable daemon is a runtime condition to report, not a container that
should refuse to come up.

## Modules

    system/    the domain: the docker call boundary (on qits-containers-driver), the parsers, the
               PTY subsystem
    targets/   the execution targets (qits-1481): their store, doors, allocator and runner socket
    service/   the deployable: JAX-RS routes, the terminal WebSocket, the boot sweep, Quinoa

`system/` is a library jar with no JAX-RS in it. `service/` is `<packaging>quarkus</packaging>` and
carries the client submodule at `service/src/main/webui` (qits-system-platform-frontend).

## The two docker output shapes

Everything about the parsers follows from this, so it is worth knowing before reading them:

- `docker <thing> ls --format '{{json .}}'` prints **one JSON object per line** — the CLI's own
  columns, already humanised (`"Status":"Up 4 hours (healthy)"`, `"Size":"325MB"`). Not an array.
- `docker <thing> inspect <ref>` prints **a JSON array** of the daemon's own documents — nested,
  complete, machine-shaped (`State.StartedAt` as RFC-3339).

Both are used deliberately: a list wants the humanised columns, a detail wants the real fields. A
parser written against the wrong one gets nulls and nothing notices. The swarm configs and secrets
lists are the exception (qits-2487): their only dates are timestamps the `ls` formatter prints as an
age, so they ask `ls` for the ids alone and print each row with an `inspect --format` template — one
JSON object per line again, but the daemon's fields. The suite's fixtures under
`system/src/test/resources/fixtures` are real captures of both.

Two consequences worth knowing:

- **Labels from a list column are lossy.** They arrive comma-joined and a value may contain a comma
  (a real one: `maintainer=Red Hat, Inc.`). Container labels therefore come from `inspect` only, as
  do configs' and secrets' (their rows are inspect's); the platform's volumes and networks are parsed
  from the column, because the platform's label values carry no commas.
- **Sizes are rendered text**, and the CLI offers no way to get the numbers. `HumanSize` reads them
  back (decimal, the way go-units renders them) so the client can draw a bar; the original string
  travels beside the number.

## Terminals

A terminal is a REST resource that a WebSocket attaches to. Splitting the two is what lets the
client list what is running before it connects, reconnect by address after a reload, and end a
session from a list without opening it.

    POST   /system/api/terminals   {"kind":"GLANCES"} | {"kind":"EXEC","container":"…","shell":"sh"}
    GET    /system/api/terminals   GET/DELETE /system/api/terminals/{id}
    WS     /system/api/terminals/{id}

- **GLANCES is find-or-create.** There is one host, so a second request for its monitor answers 200
  with the live one rather than 201 with a second.
- **EXEC resolves first.** The reference is validated, then handed to
  `docker container inspect --format '{{.Id}}|{{.State.Running}}|{{.Name}}'`. Unknown is 404,
  stopped is 409, unreachable is 503 — and the exec command line carries the canonical 64-hex id the
  daemon printed, never the caller's string.
- **The PTY is real**: `ForeignPty` over libc through `java.lang.foreign`, and the child opens the
  slave device itself under `setsid --ctty`. That shape is a fix, not a style — see
  `terminal/TerminalProcesses`, which carries the incident it came from.
- **Lifecycle**: the linger window (60s) is armed at creation and re-armed on the last detach; a
  DELETE ends it now. On the LAST detach of a glances session the window is 3s instead, so leaving
  the Overview stops the host monitor a few seconds later — long enough to carry it across a reload,
  short enough that nobody watches an abandoned container. Ending a glances session also
  `docker rm -f`s its container, because `--rm` fires on container exit and killing the attached CLI
  is not that. A boot sweep removes what a previous life left behind, filtered on the owner label so
  two platforms on one daemon do not reap each other.

Wire protocol (the platform's existing one, so one xterm client works everywhere): the client sends
`{"type":"data","data":"…"}` and `{"type":"resize","cols":N,"rows":M}`; the server sends raw PTY
text. On exit the server writes `[terminal exited (code N)]` and closes **1000**, which means final.
Any other close code means reconnect — and a reconnect gets the scrollback replayed.

Only `qits:admin` may open a socket. Every REST route also accepts `qits:system`, because reading
the host's shape is something a machine may do; holding a shell on the platform host is not.
`qits:admin-agent` — an ADMIN workspace's own commissioned agent — is admitted everywhere
`qits:admin` is, including the socket (qits-628 follow-up), and `qits:desk-admin` — the delivery
desk's administrator — beside it (epic qits-1621; no refusal is recorded for this service).

## Execution targets

The machines a `qits-system-runner` (components/qits-system/qits-system-runner-daemon) holds a docker
socket on, each with a number of typed slots — CI, WORKSPACE, DESK, AGENT — that qits-ci, qits-workspaces,
qits-projects and qits-agents lease. This service decides which lease runs where (`SlotAllocator`) and pushes each
runner its whole desired `estate`; the runner answers with an `inventory`.

A reservation holds slots for one type. It is not a limit: a type may use more than its reservation
while shared (unreserved) slots are free, so `ci 3/1` is valid when 2 shared slots were free.
A maximum (qits-1813, optional per type, null by default) is the limit: a type at its maximum is
granted nothing more on that target, however many shared slots are free. A patch's `maximums`
merges like `reservations` — a member left out keeps its value, an explicit `null` clears it (no
ceiling), and `0` is a ceiling (that type never runs there), never a clear. A maximum below its
type's reservation is 409 `MAXIMUM_BELOW_RESERVATION`; one above the total is harmless.
A patch that carries reservations or maximums is also held to the target's memory once it has
reported it (qits-2581): a maximum that could never be reached even alone, or reservations that
could not all be honoured at once, is 400 `SLOTS_EXCEED_MEMORY`, whose message names the limit
(`target workstation holds at most 3 CI lease(s) of 6 GiB (29.2 GiB less the 8 GiB builder), not
6`). It uses placement's figures (`SlotAllocator.settingsRefusal`): a type never granted there is
priced at nothing and not checked, an unset maximum is not checked, and a target that has not
reported its memory is held to its slots alone.

Memory is one more conjunct (qits-2362), beside the slots and never instead of them. A target that
reports its memory (`memoryMaxBytes`, the node's root `memory.max`, and `memoryTotalBytes`, its
`/proc/meminfo` MemTotal) has a lease capacity of the smaller of the two less
`qits.system.targets.node-memory-reserve` (default `2g`); a figure it does not report is skipped, so
localhost and the workstation, whose root `memory.max` is `max`, are sized by their MemTotal; every lease is charged its
`spec.limits.memory` (docker units, binary multiples; `memorySwap` is not charged, and a lease with no
memory limit is charged 0 — every owner sets one today). A lease fits only when the charges of the
target's GRANTED, RUNNING and ENDING leases, plus what the other types' unused reservations would need
(each priced at the memory limit of that type's most recently granted lease there), plus its own
charge stay within the capacity. That middle term is ignored when the reservations are ignored as
slots (`Σ r > T`). A target that reports no memory is held to its slots alone. A lease some target's slots would take but
no target's memory can hold waits with `no target has memory for a 6g WORKSPACE lease`.

Reservations the memory cannot meet are honoured in priority order, CI first (qits-2515), never
dropped whole. Starting from the capacity less the builder (below) when the target takes CI, each type
in turn — CI, then WORKSPACE, then DESK, then AGENT — is honoured as many of its reservations as the memory left
holds at its price (`h[u] = min(r[u], ⌊A / charge(u)⌋)`, all of them for a type priced at 0), and
those are taken off before the next type; the middle term then counts only the honoured reservations
a type does not use. Reservations the memory can meet are honoured whole, and nothing changes. Until
qits-2515 they were ignored in memory once their sum exceeded the capacity, which let a workspace take
ws-proof's only lease-sized memory and starved CI for hours.

The consequence on ws-proof (16 GiB reported, so 14 GiB of capacity; an 8 GiB builder; ci 2 and
workspace 1 at 6g): 14 − 8 = 6 GiB honours ci 1 and workspace 0, so it always keeps one 6g CI lease
(0 + 8 + 6 = 14) and takes no workspace (0 + 6 held for CI + 8 + 6 = 20 > 14), whichever was queued
first. At 32 GiB the same reservations are honoured whole (ci 2, workspace 1), and a workspace fits
while committed + 12 + 8 + 6 ≤ 32 — two of them, not three.

The builder is charged on a target that takes CI (qits-2503). An image build a CI lease sends to the
target's shared buildkitd runs in the builder's cgroup, outside the lease's own `limits.memory`; the
runner starts that buildkitd under `--memory` = `BUILDER_CONCURRENT_BUILDS × 8 GiB` (8 GiB: the 6g
heap of qits-projects-service's native build plus room around it). A raised cap reaches a builder that
CI leases are still using in place (`docker update --memory`), not only at its next replacement, so
the figure placement charges is the one the builder runs under. The runner reports the cap
in its inventory as `builderMemoryBytes`, stored as `builder_memory_bytes` and shown in the target's
`memory`. A target takes CI when CI is reserved there (`reservations.ci > 0`), a CI lease occupies it
(GRANTED, RUNNING or ENDING), or the lease being placed is CI — the same test the `buildkit` health
check counts by — and only then is the builder's cap added to the committed charges, once: the rule
is committed + the other types' unused reservations + builder + the lease's own charge ≤ capacity. A
workspace-, desk- or agent-only target never starts the builder and is not charged it, and a runner too old
to report the cap (null) is charged nothing for it. The builder comes off the capacity before any
reservation is honoured (qits-2515): it is fixed overhead, not a reservation. The
`node-memory-reserve` (2g) is what the node's own processes keep — the kernel, docker, the runner —
and no longer stands in for the builder.

Image-building CI leases are capped at the builder's width plus one (qits-2568). qits-ci asks for a
CI lease with `builds: true` when the run's pipeline has a `build: true` step, and the runner reports
its build gate's width (`BUILDER_CONCURRENT_BUILDS`, the builds qits-ci-daemon's gate lets onto the
buildkitd at once) as `builderConcurrentBuilds`, stored as `builder_concurrent_builds` and shown in
the target's `memory`. A lease that builds is granted on a target only while fewer than width + 1 of
its GRANTED, RUNNING and ENDING leases build: one past the width keeps the builder fed, and a lease
beyond that would only wait at the gate holding its whole charge. A lease that builds nothing is
never held by it, so the memory goes to the short runs instead. A capped lease waits with `no target
has builder room for an image-building CI lease` (or, pinned, the target and its builder named); a
runner too old to report the width (null) caps nothing.

What that costs, counted rather than lost: ws-proof (16 GiB, so 14 GiB of capacity, CI reserved 2)
holds one 6g lease beside its 8 GiB builder (6 + 8 = 14), not two — a CI lease, since qits-2515; the workstation (31.2 GiB, so
29.2 GiB of capacity) goes from four concurrent 6g leases to three once it takes CI (4 × 6 + 8 = 32 >
29.2). That is the builder's memory counted, which those leases' image builds were using all along
outside their caps — not a regression.

    POST   /system/api/targets                       declare one; answers its install line, once
    GET    /system/api/targets[/{id}[/health]]       slots, reservations, maximums, used, login, ...
    PATCH  /system/api/targets/{id}                  description, reservations, maximums (409
                                                     RESERVATIONS_EXCEED_TOTAL, MAXIMUM_BELOW_RESERVATION;
                                                     400 SLOTS_EXCEED_MEMORY)
    DELETE /system/api/targets/{id}                  409 TARGET_BUSY while a lease is live; else Retire
    POST   /system/api/targets/{id}/registration-token | greenlight | healthcheck | login-check
    POST   /system/api/targets/{id}/sign-ins        {harness: claude|kimi}: 202 STARTING; the runner logs in
    GET    /system/api/targets/{id}/sign-ins/{sid}  poll: AWAITING_CODE/AWAITING_BROWSER carry url, userCode
    POST   /system/api/targets/{id}/sign-ins/{sid}/code   {code}: relayed to the runner's login
    DELETE /system/api/targets/{id}/sign-ins/{sid}  CANCELLED; admin, admin-agent and desk-admin only, all four
    GET    /system/api/targets/install.sh            the toolkit's InstallScript
    POST   /system/api/targets/{id}/register         operationId registerTarget; the runner's client
    POST   /system/api/leases                        operationId createLease; 201 QUEUED
    GET    /system/api/leases[/{id}]                 listLeases / getLease; a service sees its own
    PUT    /system/api/leases/{id}/spec              replaceLeaseSpec: a roll
    DELETE /system/api/leases/{id}[?reason=]         endLease: ENDING, then ENDED; operators end any
    DELETE /system/api/work-volumes/{workKey}        purgeWorkVolume
    GET    /system/api/targets/{id}/docker/containers[/{name}]   ps -a / inspect (secrets ***)
    GET    /system/api/targets/{id}/docker/containers/{name}/logs?tail=&since=   text/plain
    POST   /system/api/targets/{id}/docker/containers/{name}/exec    {argv, timeoutSeconds}
    POST   /system/api/targets/{id}/docker/containers/{name}/stop    docker stop -t 10
    DELETE /system/api/targets/{id}/docker/containers/{name}         docker rm -f
    GET    /system/api/targets/{id}/docker/volumes[/{name}]          volume ls / inspect (with Size)
    GET    /system/api/targets/{id}/docker/audit?limit=100           target_audit rows, newest first
    GET    /system/api/leases/{id}/logs?tail=&since=                 a lease's own container log
    WS     /system/targets/socket                    qits:target-runner; sub is target.client_id

- **The owner of a lease is the caller**: its bearer's client id without `<environment>-`, checked
  against `qits.system.targets.tenants.<app>=<TYPE>` (qits-ci CI, qits-workspaces WORKSPACE,
  qits-projects DESK, qits-agents AGENT). Anyone else is 403; another type is 403 `SLOT_TYPE_NOT_YOURS`.
- **A slot runs only its type's images** (`qits.system.targets.slot-images.<type>`, 400
  `IMAGE_NOT_ALLOWED`), never as root, and never with a policy-owned env key (`HOME`,
  `CLAUDE_CONFIG_DIR`, `BUILDKIT_HOST`; 400 `ENV_KEY_NOT_ALLOWED`).
- **A WORKSPACE, DESK or AGENT lease waits on a target nobody signed in** — `detail` names the command:
  `qits targets sign-in --target <name>`, which signs in through the sign-in doors below, as the
  Targets page does. `loginCommand`/`kimiLoginCommand` are the manual fallback, run on the node:
  rendered from the volume the runner reported, once its `loginState` arrived on the current
  connection.
- **A sign-in runs on the target** (qits-1593): the runner runs the harness's own login in its
  credential volume and reports `signInProgress`; this service holds it in memory, relays the code,
  expires it after 10 minutes (EXPIRED, `signInCancel`) and keeps a settled one readable ~15. 409
  `TARGET_UNAVAILABLE`, `SIGN_IN_RUNNING` (one per target and harness), `SIGN_IN_NOT_AWAITING_CODE`;
  404 `SIGN_IN_NOT_FOUND`. The code is never logged. `claude` reads PRESENT only once the volume
  also holds Claude Code's first-run keys (the runner merges them in), so PRESENT means an agent
  launch starts without a setup screen.
- **Ending a lease**: its owner (`qits:system`, 403 `NOT_YOUR_LEASE` on another's) or an operator
  (`qits:admin`, `qits:admin-agent`, `qits:desk-admin`; an operator role wins over `qits:system`),
  who ends any lease. QUEUED or targetless is ENDED at once, and so is EXITED or FAILED (qits-1813):
  its slot was freed when it stopped and may be granted again, so it must not occupy one a second
  time; the estate drops it and the runner removes the container. GRANTED or RUNNING is ENDING until
  the runner reports the container gone — or ENDED at once when its target holds no connection,
  since no runner is there to report it (qits-2227); a runner that comes back removes it. An operator's end of an ENDING lease is ENDED
  at once — the stuck-lease door — and a lease an operator moves carries `detail = "ended by
  <principal>[: <reason>]"`. Every operator call, a refused one too, is a `target_audit` row
  (`LEASE_END`; outcome ENDED, ENDING, NOOP or NOT_FOUND).
- **ENDING has a deadline** (`control/LeaseEndingDeadline`): past `ending_since +
  qits.system.leases.ending-deadline` (PT10M) its target's estate is sent once more; past that nudge
  `+ ending-grace` (PT2M) — or at the deadline, when the target is not connected — it is ENDED with
  `detail = "ENDING deadline passed: the runner on <target> never reported the container gone (since
  <ending_since>)"`, announced, and audited as `LEASE_ENDING_DEADLINE` by `qits-system`.
  When the lease was nudged and its target is still connected, the runner has stopped acting on
  its estate: the target is quarantined first (`its runner did not act on its estate: lease <id>
  stayed ENDING past the deadline`), its GRANTED leases are queued again, and only then is the lease
  ENDED, so the pass that follows cannot grant the freed slot back to it (qits-1829). A health check
  that passes reinstates it.
- **A GRANTED lease on a target that is gone goes back to QUEUED** (qits-1829, same sweep): no open
  connection for `qits.system.targets.reconnect-grace` (counted from this process's start for a
  target it has not seen) and no work key — a work volume pins its lease to its target, so that one
  is left GRANTED. `target_id` and `granted_at` are cleared, `detail = "target <name> went away
  before it started the lease (granted <grantedAt>); queued again"`, announced, and audited as
  `LEASE_REQUEUED` by `qits-system`. RUNNING, EXITED, FAILED and ENDING leases are left alone.
- **A target gone past `qits.system.leases.absent-deadline` (PT30M) has its leases settled**
  (qits-2227, same sweep): no open connection for that long — counted from its last connection's
  close, or from this process's start for a target it has not seen since — and every RUNNING CI
  lease on it is FAILED and every ENDING lease of any type ENDED, `ended_at` the sweep's
  instant, `detail = "target <name> offline since <instant>"`, announced, and audited as
  `LEASE_ABANDONED` by `qits-system`. The deadline is long because the runner's socket crosses the
  edge, which an outage can cut while the node runs on (2026-11-12: 85 minutes). RUNNING WORKSPACE,
  DESK and AGENT leases are left alone: their agent sessions survive an outage of any length today, and a
  FAILED lease's container is removed by the runner that comes back. A GRANTED CI lease never gets
  this far — it has no work key, so the requeue above takes it after the reconnect grace.
- **`target_audit`**: who did what to a target or lease beyond its owner's doors (`TargetAuditLog`,
  its own transaction per row): actor, `qits:*` roles, target, lease, operation, outcome, detail.
- **Docker on a target, over the runner socket** (epic qits-1621, `api/TargetDockerController`,
  `api/LeaseLogsController`): `qits:admin`, `qits:admin-agent` and `qits:desk-admin` only; `{id}` is
  the target's UUID or its name. Each call sends one `docker` frame (`runner/TargetDocker`) and
  waits for its `docker-result` — `qits.system.targets.docker.timeout` (PT30S), an exec its own
  `timeoutSeconds` (default 30, at most 120) plus `docker.exec-grace` (PT10S) — on the REST worker
  thread, never the socket's. The runner answers only for its own resources (name prefix and owner
  label). OK 200; NOT_FOUND 404; REFUSED 403 `DOCKER_REFUSED` (the runner's own container, a bad
  argument); FAILED 502 `DOCKER_FAILED` with docker's words; no answer 504 `DOCKER_TIMEOUT`; no live
  socket 409 `TARGET_UNAVAILABLE` "target <name> is not connected". A log is `text/plain` with
  `X-Qits-Truncated: true|false` (the runner caps at 1 MiB); an exec answers `{exitCode, stdout,
  stderr, truncated}`. An inspect's `Config.Env` `KEY=VALUE` and `Config.Labels`/`Labels` values, and
  a listing row's `Labels`, are `***` where the key matches
  `(?i)(TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIAL|API_?KEY|PRIVATE_KEY|AUTH)` (`control/DockerRedaction`);
  a log and an exec's output are redacted by the runner. `GET /leases/{id}/logs` finds the lease's
  target, lists its containers and takes the one labelled `qits.system.runner.lease=<id>`.
  **Every call is one `target_audit` row written before the answer** — `DOCKER_<OP>`, the container
  or volume, `args {tail, since, argv, timeoutSeconds}`, outcome `OK | NOT_FOUND | REFUSED | FAILED |
  TIMEOUT | NOT_CONNECTED | BAD_REQUEST` (an unknown target is NOT_FOUND with no target id); reading
  the audit is not itself audited.
- **OOM kills** (qits-1818): an `inventory` carries the node's memory (`nodeOomKills`,
  `kernelOomKills`, `memoryMaxBytes`, `memoryTotalBytes` since qits-2362, `builderMemoryBytes` since
  qits-2503) and each lease's (`oomKills`, `oomEvents`, `memoryPeakBytes`,
  `oomKilled`), all nullable. A target shows them as `memory {nodeOomKills, kernelOomKills,
  memoryMaxBytes, memoryTotalBytes, builderMemoryBytes, reportedAt}`, a lease as `oomKills`, `oomScope` (`LEASE` when it hit its own limit,
  `NODE` otherwise, null without a kill — `Lease.oomScope`), `memoryPeakBytes`, `oomKilled`. A
  lease's counters and peak never move down and a missing value keeps the stored one, so an EXITED
  lease keeps its last sample; an old-shaped inventory changes none of them.
- **Events**: `TargetLeaseChanged` on every state change, and when a lease's `oomKills` rose (with
  `oomKills`, `oomScope`; a sample without a kill publishes nothing); `TargetOomKilled` when a
  target's node counter (`nodeOomKills`, else `kernelOomKills`) rose — `delta`, and the `projectIds`
  of the leases occupying it then; a first value is the baseline and a drop (a reboot) the new one;
  `TargetLoginChanged` on every sign-in change, `TargetConnectionChanged` when whether a target's
  runner serves it flips (a handover publishes nothing); `TargetDeleted` (qits-2022) once `DELETE
  /targets/{id}` decommissions a target — never for a 404 delete. Owners also reconcile from `GET
  /leases`.
- **Registration check** (qits-2022): `POST /targets/{id}/registration-check` `{subject}`,
  `qits:system` only — 200 `{targetId, targetName}` when `subject` is the target's live
  registration token's subject, 403 otherwise (the subject differs, or none is live), 404 unknown
  target. The same comparison `register` makes, for a service with its own idp client (eg.
  qits-network-access) to ask without attempting a registration itself; it never answers what the
  stored subject is.
- **Store**: postgres (`resources: postgresql:db, postgresql:eventstream:…` in deployments.yml),
  flyway `db/system/migration`, named persistence unit `system`.

## Config

All under `qits.system.*`, defaulted in the domain jar at ordinal 100 and overridable per
deployment as `QITS_SYSTEM_*`. The defaults and the reasoning are in
`system/src/main/resources/META-INF/microprofile-config.properties`; the short list:

    docker.binary=docker                  docker.call-timeout=PT20S
    docker.max-output-chars=2000000       docker.config-dir=/tmp/qits-docker
    logs.max-tail=5000                    logs.max-chars=262144
    glances.image-repo=mirror.dev.localhost:8080/hub/nicolargo/glances
    glances.image-version=4.5.6-full      glances.args=      glances.pull-at-startup=true
    terminals.linger=PT60S                terminals.glances-linger=PT3S
    terminals.max-sessions=8
    terminals.scrollback-bytes=262144     terminals.write-timeout=PT5S
    terminals.owner=${quarkus.application.name}

One more is the service's own, in `service/src/main/resources/application.properties`:
`terminals.origin-domain=qits.${QITS_DOMAIN}` (qits-2356), the anchor the terminal socket's origin
check admits besides the request's own host — `https` on the default port, that name or any name
under it — so the landing app's apex can dial the socket while a foreign page still gets a 403.

The targets module's keys are `qits.system.targets.*`, defaulted in
`targets/src/main/resources/META-INF/microprofile-config.properties`: `tenants.<app>`,
`slot-images.{ci,workspace,desk,agent}`, `probe-image` (optional: the probe runs the newest WORKSPACE,
else DESK, else AGENT, lease's image first), `registry-mirrors.{registry-hosts,mirror-hosts,
upstreams}`, `runner-version-override`, `reconnect-grace=PT2M`, `commission-reconcile-interval=1h`,
`public-url` (wss base), `estate-interval=PT1M`, `node-memory-reserve=2g` (qits-2362: a target's lease capacity is min(memoryMaxBytes, memoryTotalBytes) less this, kept for the node's own processes; the shared buildkitd is charged separately, at its reported builderMemoryBytes, on a target that takes CI — qits-2503), `healthcheck.schedule`, `healthcheck.timeout=PT5M`,
`dialback.threshold=3` and `dialback.cooloff=PT30M` (qits-1809: that many RUNNING CI leases in a row
ended `never-dialled-back` — or CI leases that ran and EXITED before that end, qits-2382 — quarantine
the target, and the gating `dialback` check holds it for the
cool-off; a GRANTED CI lease its runner reports FAILED, one it could not start at all, counts in the
same streak, qits-525, so a target that cannot start CI leases is quarantined with a `start:` reason
instead of being granted run after run to fail),
`idp.{url,client-id,client-secret}`, `docker.timeout=PT30S`, `docker.exec-grace=PT10S`. Beside them, `qits.system.leases.ending-deadline=PT10M`, `absent-deadline=PT30M`,
`ending-grace=PT2M` and `ending-check-interval=60s` (the ENDING deadline).

## Building

    ./mvnw clean verify -Dquarkus.http.test-port=0

Needs a node on PATH and `git submodule update --init` (Quinoa builds the client during
augmentation). Port 0 is not optional on the deployment host: 8081 there is the platform's own npm
registry. Always `clean` — incremental compilation leaves stale generated classes behind.

Add `-DskipITs=false` to run `PackagedSurfaceIT` against the fast-jar, and `-Dnative` to build the
binary and run the same IT against it. The native build is the only thing that proves the FFM
metadata and `setsid` survived into the image.

No docker is needed for any of it: the suite points `qits.system.docker.binary` at a shell script
that answers like docker and turns `exec -it` into a real echo loop on a real pseudo-terminal.
