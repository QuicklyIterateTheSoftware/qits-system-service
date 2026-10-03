# qits-platform-system — working notes

Read `README.md` first: it defines what this service reads, the two docker output shapes, the
terminal lifecycle and the config keys. This file is the working conventions on top of it.

## The rules that shape everything

**This service READS; it does not act.** No scale, no restart, no rm of anything it did not itself
create, no config or secret write. The vocabulary is reads plus `run`/`exec` of a terminal and
`rm -f` of this service's own labelled containers. Adding a write is a decision about what this
console IS, and it belongs in `qits-system-plan.md` before it belongs in a commit here.

**The contract is pinned by `qits-system-plan.md` in the qits-qits wrapper.** The route shapes, the
wire names, the config keys and the terminal protocol are written down there and two repositories
build against them. Changing one of those shapes is a plan edit and a conversation.

**A clone of this repo alone builds and tests green** — no monorepo, no docker, no prior
`mvn install` elsewhere, no credentials. That is why the poms duplicate versions instead of
inheriting them, and why the suite forks a shell script instead of a daemon.

**The one thing it needs besides Maven Central** is the platform's own Maven repository, for
`qits-auth-core` and `qits-arch-rules`. `<repositories>` in the root pom points at
`${qits.maven.repository.url}` (`https://registry.qits.wohlben.eu/artifacts/maven/maven` by
default), which answers 401 without the commissioned client; the image build supplies it through
`.qits-maven-settings.xml`, which mirrors the exact repository id `qits-maven` — an exact id match
is what gets past Maven's `external:http:*` blocker.

**The gate is `./mvnw clean verify -Dquarkus.http.test-port=0`**, and it needs BOTH a node on PATH
and `git submodule update --init`. Always `clean`. Port 0 is not optional on the deployment host.

## Docker

**Every call goes through `DockerCli`, and every argv through `DockerArgv` or `TerminalArgv`.** The
builders are pure functions with no I/O, which is what lets the suite assert them element for
element — and the argv IS the sandbox, so a flag lost in a refactor is invisible everywhere else
until it is invisible in production.

**A failed call is a 503 carrying docker's own last words. Never a 404, never an empty list.** A
read that could not reach the daemon knows nothing. The three failures an operator actually meets —
socket not mounted, group not added, node is not a manager — all arrive as a non-zero exit with one
clear sentence, and passing that sentence through is worth more than anything this service could
invent. The two callers that must tell "docker said no" from "docker could not be asked" use
`attempt` and match on docker's wording; they are `SwarmReads.inspectOne` and `NodeReads`.

**Two checkpoints on every caller string, and neither is trusted alone.** The controller validates,
and `DockerIdentifiers` validates again inside the argv builder. What they are really for is the
element boundaries a value could forge — a leading `-` read as an option, a `:` moving a mount's
fields.

**The exec target is resolved, not passed through.** `NodeReads.requireRunningContainer` hands the
reference to the daemon and keeps only the canonical 64-hex id it printed;
`DockerIdentifiers.requireFullId` in the argv builder is the belt that makes bypassing it fail.

**Never add a captured call without a timeout and a bound.** Both are required parameters of
`DockerProcess.run` on purpose: a convenience overload is the regression.

## Parsers

**Fixtures are real captures, and a new field needs a new capture.** `system/src/test/resources/
fixtures/README.md` says which files are captured and which two are hand-written because this host
has no swarm configs. A parser written from docker's API documentation rather than from its CLI
output reads field names that are not there.

**Every field is optional.** Docker renames and drops columns between releases, and one absent
field must cost one blank value, not a panel. `Json` is where that tolerance lives; nothing else
should be reaching into a `JsonNode` by hand.

**Wire names are the CLIENT's vocabulary, not docker's.** `hostname` not `Name`, `os` not
`OperatingSystem`, `dockerVersion` not `ServerVersion`. The parser is where the two meet, and that
is the only place they should.

**Environment values never leave this service.** `Json.envKeys` is the only way an `Env` array is
read, and two tests assert that the fixtures' scrubbed secret marker reaches no record. If a future
detail view needs more of a container, it still does not need this.

## The terminal subsystem

**It is copied from qits-projects-service, incident and all.** `ForeignPty`, its reachability
metadata and `TerminalProcesses.terminalProcess` are that repository's, and the comment on the last
one is the production outage it was written for: `ProcessBuilder`'s file redirects are opened by the
CALLING process, and a session leader opening a pts adopts it as its controlling terminal. Do not simplify
that launch. `HangupImmunity` is the backstop under it.

**The reader is a PLATFORM thread, not a virtual one.** The read is a blocking FFM downcall, which
pins its carrier for the life of the session.

**One stateful decoder per session.** A multi-byte character straddles a 4 KiB read boundary about
once a second when a full-screen program is drawing; decoding each read on its own turns every one
of them into a replacement character that never heals. Replay decodes the ring snapshot separately —
feeding the live decoder the scrollback would corrupt both.

**Create is separate from attach, and the linger window is armed at CREATION.** A POST whose browser
never connects must not leave a container running with nothing pointing at it.

**Glances stops when the last viewer leaves; a shell does not.** The last detach arms
`terminals.glances-linger` (3s) for GLANCES and `terminals.linger` (60s) for EXEC. A host monitor
holds nothing worth coming back to, so navigating away from the Overview must stop it — but not
instantly: a reload closes and reopens the socket within a second or two, and the session is shared.
The CREATION window stays the long one for both, because nobody has attached yet.

**Ending a glances session is two acts.** `--rm` fires when the CONTAINER exits; killing the CLI
attached to it is not that. The second act is `docker rm -f` by the name the argv builder derived
from the session id, and the boot sweep is what catches the ones a killed service left behind.

**The sweep filters on the owner label's VALUE.** Two platforms can share one docker daemon, and a
sweep that reaped every `qits.system.owner` container would kill the other platform's live terminals
on every restart of this one.

## The wire

**Anything returned as `Response.entity(...)` is invisible to the build-time Jackson analysis**,
which is what `api/ApiWireReflection` exists for — including nested records, which registering the
outer one does not reach. A new response type joins that list in the commit that adds it; the
failure is a 500 in the native binary while every JVM test stays green.

**`{"message": "…"}` and nothing else**, plus `"code"` on the one refusal a client branches on
(`NODE_REMOTE`). One exception mapper, so two routes failing for the same reason cannot answer
differently.

**The WebSocket path is a literal.** `@WebSocket` does not follow `quarkus.rest.path`, so
`/system/api/terminals/{id}` is spelled in `TerminalSocket.PATH_PREFIX` and moves with that key by
hand. `TerminalView` builds `socketPath` from the same constant so the client never spells it twice.

**Add a literal route under `/system`, add its prefix to `quarkus.quinoa.ignored-path-prefixes` in
the same commit.** Without an entry the SPA fallback answers the page with 200 text/html, and a
JSON parser gets an HTML document.

## Tests

**No mocks.** The suite substitutes the docker BINARY, not a class: `qits.system.docker.binary`
points at `service/src/test/resources/fake-docker/docker`, which answers the reads in docker's exact
shapes, refuses an unknown container in docker's own words, and turns `run -it`/`exec -it` into a
real echo loop. Everything between the HTTP request and that child is the shipped code, including a
real pseudo-terminal.

**`FakeDockerConfigSource` is a config source rather than a per-test profile** so nobody can forget
it and silently run against the developer's real docker.

**A test must leave no session behind.** Each terminal is a PTY and a child; the `@AfterEach` in the
terminal suites ends every one.

**`PackagedSurfaceIT` is the only proof of three things**: the FFM metadata and the run-time
initialisation of `ForeignPty`, `setsid` being present in the runtime image, and the client being
served with a matching `<base href>`. Run it with `-DskipITs=false`, or with `-Dnative`, which flips
the flag itself.

**`TokenValidationBootstrapIT` is the only place the OIDC tenant is ever ON.** The shipped tenant is
gated on `qits.auth.machine.required`, which every surefire suite here leaves false, so the whole
`quarkus.oidc.*` block runs nowhere else. `stories/support/StoryProfile` is what turns it on. Its far
side is qits-service-mock's `MockIdp`, which serves a real JWKS for a generated keypair, mints tokens
against it and records what it answered.

**Never run two native builds on this platform's host at once.** They OOM a 16 GB machine and take
it down with them.

## The story catalogue

**Seven `@UserStory` methods over four `@QuarkusIntegrationTest` classes**, emitting
`service/target/userstories/` and published by the verify half of the release-request phase
(`.config/qits/release.yml` declares the `java-service` archetype and overrides no slot), once per
release-request fold, as `@userflows/qits-system-platform-service` — the repository's own name,
which is what `userflows: true` means. That half **gates**, like every step of the composed
pipeline: a red story is a red verdict for the whole fold and holds it at the release gate, and
there is no per-step exemption to declare — qits-ci refuses one. `skipITs` stays true and the
pipeline names the classes; a new story class joins `.config/qits/userflow-stories` — one class name
per line, which the archetype turns into `-Dit.test` — **in the same commit**, or it is silently
never run.

    api/TokenValidationBootstrapIT      authentication  the packaged boot: the JWKS fetch, the boot
                                                        sweep, a peer's bearer, and a stranger's
    stories/host/HostOverviewIT         the host        the machine, its node, and the swarm — and
                                                        never an env value or a secret
    stories/terminals/TerminalRefusalIT terminals       who may hold a shell here, and what a refused
                                                        one costs the daemon (nothing)
    stories/terminals/TerminalSessionIT terminals       a real shell on a real PTY, and the host
                                                        monitor's two-act ending

**One `StoryProfile` for the whole catalogue, and that is the point of it.** Every class carries
`@TestProfile(StoryProfile.class)`, so failsafe launches the fast-jar **once**: one boot, one
terminal registry, one docker recording. A second profile would be a second process whose startup
traffic landed in whichever diagram happened to be open.

**Order is load-bearing, not tidiness.** A cumulative source is attributed by a cursor, so
pre-story traffic — the startup JWKS fetch and the boot sweep's two docker calls — lands in
whichever story drains FIRST. `@UserflowRunsAfter(TokenValidationBootstrapIT.class)` on every other
story is what keeps that the story it belongs to (`UserflowClassOrderer` is registered as junit's
secondary orderer in the test `application.properties`; a local `junit-platform.properties` hard-fails
surefire). Run a later class on its own and its first story inherits those calls and fails its edge
count — loudly, which is the right way for that assumption to break.

**The diagram is observed, never narrated.** `Interactions.happened()` was removed from the framework
in 2026.829 and there is nothing to replace it with. Four passive feeds and one declaration:

- **`NetworkTaps.restAssured("qits-platform-system")`** — the framework SHIPS the tap now; the
  per-repo `StoryNetworkFilter` copy was deleted in the commit that added the catalogue. It turns
  every RestAssured request into `<actor> -> qits-platform-system` labelled with the status this
  service answered, skipping any path with a `/q/` segment — the probe root here is `/system/q`, so
  the default is right without an override. It is idempotent per service name, which is why every
  story class installs it from its own `@BeforeAll` and nothing is drawn twice. A story sets
  `NetworkCapture.actor(...)` **before** each call, because a tap sees a request and never a role.
- **`MockIdp`'s request log**, a cumulative `NetworkCapture.source`, for the startup JWKS fetch.
- **`stories/support/StoryDocker`**, also cumulative, for every docker call — and this is where the
  wave-5 shape changed. The docker hop is no longer declared, because it is genuinely observable:
  `DockerProcess` spawns the CLI and reads its pipes, so `StoryDocker` STAGES the shipped
  `fake-docker/docker` script into `target/story-docker/` and points the launched artifact there.
  The copy is the point — `target/test-classes/fake-docker/` is written by the whole surefire suite,
  which finishes before failsafe starts, so a recording read from there would open with several
  hundred calls belonging to unit tests. The staged log is written by exactly one process, from its
  first boot call onwards, which is why the source is registered at **zero with no floor**.
- **The terminal socket**, instrumented at its own call sites in `stories/support/StoryTerminal`:
  the dial as one `socket` edge, each frame as an `event` edge in the direction it was pushed. Every
  observation there is synchronous **on the story thread** — a listener callback would read whatever
  actor is current when the frame lands, which is a different story's. A refusal is recorded in the
  catch block, so a "refused" arrow can never be drawn for a handshake that was allowed.

**The one declared edge is the socket BEHIND the CLI**, `docker -> the host's docker daemon`. That is
what `Network.declare` is for: no port to sit in front of, no request log that is the daemon's rather
than the fixture's. Everything on this side of it is evidence.

**A label is a summary, not an argv.** `StoryDocker.summarize` reduces a command line to
`info`, `system df`, `exec -it {digest} sh`, and appends the exit code as ` -> 0` — the shape an
HTTP label's status has, because it is the same half of the evidence. The whole argv would move the
`networkHash` on every run (a terminal argv carries `QITS_SYSTEM_SESSION=<uuid>`, a glances argv a
`--name` derived from it) and a Go `--format` template's braces are mermaid syntax. The argv is
still assertable in full through `StoryDocker.argvOf`, which is where the sandbox claims live — the
diagram carries summaries, a claim about a flag reads the argv.

**The absences are the paying assertions.** `assertEdgeCount` on every story, `assertNoEdgesTo`
(`docker`, the daemon, the idp) where the title is a claim about what did NOT happen, and
`assertNotLeaked` for every minted bearer and every generated session id. An absence is a step and
never an edge.

**The linger reaper is deliberately not exercised, and that is a stated gap.** Both windows keep
their shipped values (60s, 3s) — the surefire `application.properties` shortens them and
`StoryProfile` does not. A story that waited out a window would be indistinguishable from a story
that hung, and a session reaped by a timer would put its `docker rm -f` in whichever diagram was
open when it fired. Every terminal story ends its own session; `TerminalLingerTest` keeps that
coverage.

**The fake terminal's resize handling was wrong, and a story is what found it.** The loop used to
read "a read interrupted by a signal returns above 128; anything else is end of input", which is
bash's behaviour and not dash's: dash answers 1 for both. The session therefore ended silently on
the line after every resize — with the new size already printed, so `TerminalSocketTest`'s resize
test, which waits for the size and then stops, passed anyway. The trap sets a flag now. The general
lesson is the one worth keeping: a fixture assertion that stops at the moment of interest cannot
see what the moment after it destroyed.

