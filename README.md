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
| what is this swarm | `docker node ls/inspect`, `service ls/inspect/ps`, `config ls/inspect`, `secret ls` |
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
  shown, decoded — that is what a config is for.
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

With no socket the service still starts, serves its client, and answers 503 on every read. That is
the honest behaviour: an unreachable daemon is a runtime condition to report, not a container that
should refuse to come up.

## Modules

    system/    the domain: docker argv builders, the process runner, the parsers, the PTY subsystem
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
parser written against the wrong one gets nulls and nothing notices. The suite's fixtures under
`system/src/test/resources/fixtures` are real captures of both.

Two consequences worth knowing:

- **Labels from a list column are lossy.** They arrive comma-joined and a value may contain a comma
  (a real one: `maintainer=Red Hat, Inc.`). Container labels therefore come from `inspect` only; the
  platform's own objects — configs, secrets, volumes, networks — are parsed from the column, because
  the platform's label values carry no commas.
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
`qits:admin` is, including the socket (qits-628 follow-up).

## Execution targets

The machines a `qits-system-runner` (components/qits-system/qits-system-runner-daemon) holds a docker
socket on, each with a number of typed slots — CI, WORKSPACE, DESK — that qits-ci, qits-workspaces and
qits-projects lease. This service decides which lease runs where (`SlotAllocator`) and pushes each
runner its whole desired `estate`; the runner answers with an `inventory`.

    POST   /system/api/targets                       declare one; answers its install line, once
    GET    /system/api/targets[/{id}[/health]]       slots, minimums, used, login, connected, ...
    PATCH  /system/api/targets/{id}                  description, minimums (409 MINIMUMS_EXCEED_TOTAL)
    DELETE /system/api/targets/{id}                  409 TARGET_BUSY while a lease is live; else Retire
    POST   /system/api/targets/{id}/registration-token | greenlight | healthcheck | login-check
    POST   /system/api/targets/{id}/sign-ins        {harness: claude|kimi}: 202 STARTING; the runner logs in
    GET    /system/api/targets/{id}/sign-ins/{sid}  poll: AWAITING_CODE/AWAITING_BROWSER carry url, userCode
    POST   /system/api/targets/{id}/sign-ins/{sid}/code   {code}: relayed to the runner's login
    DELETE /system/api/targets/{id}/sign-ins/{sid}  CANCELLED; admin and admin-agent only, all four
    GET    /system/api/targets/install.sh            the toolkit's InstallScript
    POST   /system/api/targets/{id}/register         operationId registerTarget; the runner's client
    POST   /system/api/leases                        operationId createLease; 201 QUEUED
    GET    /system/api/leases[/{id}]                 listLeases / getLease; a service sees its own
    PUT    /system/api/leases/{id}/spec              replaceLeaseSpec: a roll
    DELETE /system/api/leases/{id}                   endLease: ENDING, then ENDED
    DELETE /system/api/work-volumes/{workKey}        purgeWorkVolume
    WS     /system/targets/socket                    qits:target-runner; sub is target.client_id

- **The owner of a lease is the caller**: its bearer's client id without `<environment>-`, checked
  against `qits.system.targets.tenants.<app>=<TYPE>` (qits-ci CI, qits-workspaces WORKSPACE,
  qits-projects DESK). Anyone else is 403; another type is 403 `SLOT_TYPE_NOT_YOURS`.
- **A slot runs only its type's images** (`qits.system.targets.slot-images.<type>`, 400
  `IMAGE_NOT_ALLOWED`), never as root, and never with a policy-owned env key (`HOME`,
  `CLAUDE_CONFIG_DIR`, `BUILDKIT_HOST`; 400 `ENV_KEY_NOT_ALLOWED`).
- **A WORKSPACE or DESK lease waits on a target nobody signed in** — `detail` names the command:
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
- **Events**: `TargetLeaseChanged` on every state change, `TargetLoginChanged` on every sign-in
  change. Owners also reconcile from `GET /leases`.
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

The targets module's keys are `qits.system.targets.*`, defaulted in
`targets/src/main/resources/META-INF/microprofile-config.properties`: `tenants.<app>`,
`slot-images.{ci,workspace,desk}`, `probe-image` (optional: the probe runs the newest WORKSPACE, else
DESK, lease's image first), `registry-mirrors.{registry-hosts,mirror-hosts,
upstreams}`, `runner-version-override`, `reconnect-grace=PT2M`, `commission-reconcile-interval=1h`,
`public-url` (wss base), `estate-interval=PT1M`, `healthcheck.schedule`, `healthcheck.timeout=PT5M`,
`idp.{url,client-id,client-secret}`.

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
