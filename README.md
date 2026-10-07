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

Every answer is read from the docker daemon at the moment it is asked. There is no store, no cache
and no schema: a restart loses nothing except the live terminals, which it is meant to lose.

## What it does NOT do

- **No writes.** No scale, no restart, no rm, no config or secret create. The whole surface is read
  plus a shell.
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
