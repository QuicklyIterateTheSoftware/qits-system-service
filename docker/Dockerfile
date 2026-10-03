# qits-platform-system as a GraalVM-native container IMAGE — the same shape as every other qits
# service's docker/Dockerfile: a Mandrel builder stage compiles the binary, a minimal runtime stage
# carries only it plus the two programs this service actually forks.
#
# This file is not how the binary is built; it is how the binary is SHIPPED. `.sdkmanrc` names
# 25.0.2-graalce, so `sdk env && ./mvnw package -Dnative` produces service/target/qits-platform-system
# locally with no container involved. What this adds is the image around it — and a way for a machine
# with no GraalVM to get a binary at all, since the builder stage brings its own native-image.
#
# Build from the repo root:
#
#     docker build -t qits/qits-platform-system:latest -f docker/Dockerfile .
#
# THERE IS A CLIENT, and it is NOT built here — it arrives in the build context already built. The
# whole of why is with the mvnw line below, where the five flags that arrange it live. A host-side
# build therefore needs `git submodule update --init` and then `npm ci && npm run build` in
# service/src/main/webui first; the CI recipe does both in its first half.
#
# --- READ THIS BEFORE DEPLOYING: the socket is not in this image ----------------------------------
# This service does its work by shelling out to the docker CLI, so the runtime stage below is
# deliberately NOT "the binary and nothing else". TWO external programs are part of this service's
# runtime and no config value substitutes for either; both are installed below. But the CLI is only
# half of what a deployment supplies, and the other half is a decision rather than a default:
#
#   THE DOCKER SOCKET IS NOT IN THIS IMAGE. Run with
#   `-v /var/run/docker.sock:/var/run/docker.sock --group-add <the socket's gid>`, or every read
#   this service exists to serve answers 503 and no terminal starts.
#
# That mount hands this container control of the host's docker daemon, which is root-equivalent, so
# it is left as an explicit deployment act rather than baked in. `--group-add` is the other half of
# the same act: this image runs as UID 1001 in group 0, and the socket is `root:docker` 0660, so
# without the daemon's group the CLI is present and every call is a permission denial. This is the
# platform's THIRD socket holder, after qits-containers and qits-deployments; the grant is written
# down in cli-bootstrap's extras block for this app, which is where a reader should find it.
#
# `docker run` with no socket therefore starts, serves the client and answers 503 on every read.
# That is the honest behaviour: an unreachable daemon is a runtime condition this service reports,
# not a packaging error, and there is nothing an ENV here could point at that would be right.
#
# --- stage `build`: native-compile ----------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK, and this
# project targets release 25.
#
# `mirror.dev.localhost:8080/quay/…` is qits-platform-mirror's pull-through mirror of quay.io: the
# first build fetches the image from upstream and caches it, every later build is served from local
# disk. The tag is upstream's, unchanged — the mirror revalidates it, so `jdk-25` still moves when
# quay moves it.
FROM mirror.dev.localhost:8080/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
USER root
WORKDIR /src

# Fetch the Maven distribution first, keyed only on the wrapper files, so a source-only change does
# not re-download it.
COPY mvnw .
COPY .mvn/ .mvn/
RUN ./mvnw --version

# The whole reactor, not a module list. `-am` builds service's dependency from source —
# `qits-platform-system-domain` is a version this repo never publishes, so a builder container with
# an empty ~/.m2 has nowhere else to get it. Copying module by module would mean editing this file
# every time one is added, and forgetting to would fail the build with an unresolved dependency
# rather than anything that names the real cause. `.dockerignore` is what keeps this cheap.
COPY . .

# --- where the two qits jars come from ------------------------------------------------------------
# This repo depends on qits-auth-core (the forward-auth mechanism and the machine gate) and
# qits-arch-rules, which live only on the platform's own Maven repository. Both platform hosts are
# code plus one input, the public domain:
#
#   https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven   hosted Maven (qits-artifacts)
#   https://mirror.qits.$QITS_DOMAIN/mirror/maven/central      Maven Central cache (qits-mirror)
#
# The RUN below derives both and exports them for that RUN alone; `.qits-maven-settings.xml` reads
# them as ${env.…}, mirroring the `qits-maven` repository id onto the first and Central onto the
# second — an exact id match, which is what gets past Maven's external:http:* blocker without
# permitting arbitrary HTTP repositories. The CI recipe passes QITS_DOMAIN; the default is the live
# platform's, so a plain host-side `docker build` still works.
ARG QITS_DOMAIN=wohlben.eu

# --- and how Maven authenticates, once that repository stops answering anonymously -----------------
# The two secret mounts put the CI run's commissioned client into THIS `RUN`'s environment and
# nowhere else — not a layer, not the image history. That is why they are not build args: this image
# is pullable, and a build arg is recorded where anyone who pulls it can read it.
# `.qits-maven-settings.xml` presents them as the username and password of both mirrors.
# The `RUN` reads the two mounted files itself: docker 26's BuildKit has no `env=` mount option.
# FILE form (`/run/secrets/<id>`, read into the command's own environment), not the `env=` mount
# form: env= needs a dockerfile frontend the builder chooses, while the bare file mount is served
# identically by every frontend version — and under the platform builder the frontend is the
# builder's, not this file's, to pick. Absent secrets leave both variables empty (`|| true` is every
# hand build's state), which the settings read exactly as they read unset.
#
# -Dnative flips quarkus.native.enabled (service/pom.xml carries its own `native` profile). Cap
# native-image RAM so the build is deterministic on small machines.
#
# -DskipTests skips execution, not compilation: test sources still compile here. Running them is
# what is not possible in this stage — the PTY suites need a real /dev/pts and the API suites shell
# a fake docker script. `./mvnw verify` on a clone is the gate for those.
#
# --- node, and the three command overrides: why the client is NOT built here ----------------------
# The two `package-manager-install` flags give this stage a node: the Mandrel builder image ships
# none at all, and Quinoa shells out to npm regardless. They live here rather than in
# application.properties on purpose — a local or CI `mvnw` build must use the node on PATH, so that
# no build silently downloads a toolchain. The version is the PLATFORM pin (22.22.0), and it does not
# have to match the step container's node, because this stage does not build the bundle.
#
# service/src/main/webui is qits-platform-spa-system, and it depends on @qits/ui-components. That
# package exists ONLY on the platform's own npm registry (qits-artifacts), which answers 401 without
# a credential, and this stage is handed only the Maven one. So the install cannot happen here.
#
# A RUN step under the platform builder DOES reach the registry's address (the public host, same as
# everywhere else) — the arrangement still does not move. The SPA's committed .npmrc and its lockfile
# both name the public registries, and the credential that answers their 401 is the CI step's
# business, not this stage's; and this stage has to keep building on a developer's host too, where
# the registry answers the same 401 with no credential handed to it either.
#
# It does not merely fail, it fails unreadably. The SPA commits an .npmrc pointing at the edge's
# registry vhosts (right for a developer on the deployment host), npm ranks a project .npmrc above
# ~/.npmrc, and npm's answer to a registry that never connects is `Exit handler never called!` — its
# own internal-error text, naming neither the registry nor the address.
#
# The bundle is therefore built BEFORE this image, by the pipeline step in
# .config/qits/ci-post-receive.yml, which runs on qits-net where the registry is reachable, and
# arrives in the build context as service/src/main/webui/dist/ (see .dockerignore, which stops short
# of excluding exactly that path for this reason). `--version` is the null command: it proves the
# package manager is really there and does nothing else.
#
# THIS DOES NOT FAIL QUIETLY. The `test -f` below stops a context with no bundle before the
# multi-minute native compile, and Quinoa's own build-dir check would stop it after. A forgotten
# `npm run build` in the step is a red build, not a service that ships without its client. The same
# guard covers a context whose webui submodule is NOT initialised: it has no bundle either.
#
# --- and why the bundle is copied onto itself first -----------------------------------------------
# Quinoa does not copy build-dir into target/quinoa/build, it MOVES it, and overlayfs cannot rename a
# directory that still lives in a LOWER image layer. It answers EXDEV, the JDK falls back to its
# cross-file-store path, and that path refuses a non-empty directory — the build dies seconds in with
# `DirectoryNotEmptyException: …/dist/qits-platform-spa-system/browser`, which names the source and
# explains nothing. A builder stage that *creates* dist itself is immune by accident, because dist is
# then already in the upper layer; ours arrives by COPY, so it is not.
#
# One `cp` re-materialises it in the layer that is about to move it. It has to be in THIS `RUN` and
# not a tidier one of its own — a layer boundary is exactly what it is undoing.
RUN --mount=type=secret,id=qits-client-id \
    --mount=type=secret,id=qits-client-secret \
    export QITS_MAVEN_AUTH_USR="$(cat /run/secrets/qits-client-id 2>/dev/null || true)"; \
    export QITS_MAVEN_AUTH_PSW="$(cat /run/secrets/qits-client-secret 2>/dev/null || true)"; \
    export QITS_MAVEN_REPOSITORY_URL="https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven"; \
    export QITS_MAVEN_CENTRAL_URL="https://mirror.qits.$QITS_DOMAIN/mirror/maven/central"; \
    d=service/src/main/webui/dist/qits-platform-spa-system/browser; \
    test -f "$d/index.html" || { \
      echo "No built client SPA at $d." >&2; \
      echo "The build context must carry one: run 'npm ci && npm run build'" >&2; \
      echo "in service/src/main/webui first — this stage cannot, see the Quinoa flags above." >&2; \
      exit 1; }; \
    cp -a "$d" "$d.layer" && rm -rf "$d" && mv "$d.layer" "$d" && \
    ./mvnw -B -ntp -s .qits-maven-settings.xml -pl service -am package -Dnative -DskipTests \
      -Dqits.maven.repository.url="$QITS_MAVEN_REPOSITORY_URL" \
      -Dquarkus.native.native-image-xmx=4g \
      -Dquarkus.quinoa.package-manager-install=true \
      -Dquarkus.quinoa.package-manager-install.node-version=22.22.0 \
      -Dquarkus.quinoa.package-manager-command.install=--version \
      -Dquarkus.quinoa.package-manager-command.ci=--version \
      -Dquarkus.quinoa.package-manager-command.build=--version \
      org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom \
      -DoutputFormat=json -DoutputName=sbom -DschemaVersion=1.6 \
      -Dcyclonedx.skipNotDeployed=false
# makeBom RIDES ON THE INVOCATION ABOVE rather than getting a second one, for the same reason
# everything else in this RUN is one instruction: a second `./mvnw` would be a second layer over a
# native compile, and a re-resolve of the same reactor to produce a document the first run already
# had the model for.
#
# cyclonedx.skipNotDeployed=false is LOAD-BEARING, measured in qits-artifacts-service on its first
# release: `service` is <packaging>quarkus</packaging> and ships as this image rather than as a jar,
# so it skips maven-deploy — and the plugin SKIPS deploy-skipping modules by default, with a green
# build and one INFO line. The sbom stage's COPY then fails on a file nothing wrote. The spelling
# matters too: a bare -DskipNotDeployed=false is silently ignored, and only the cyclonedx.-prefixed
# property reaches the plugin.

# --- sbom -----------------------------------------------------------------------------------------
# The service module's CycloneDX document, exported so the release step can PUT it to the platform's
# SBOM store. A stage rather than a layer of the runtime image, and BEFORE the runtime stage on
# purpose: the last stage is what a plain `docker build` builds, and that must stay the image.
# `.config/qits/ci-event-release.yml` reaches this one with `--opt target=sbom --output type=local`
# using the SAME args and secrets as the image build, so every layer above is a cache hit and the
# export costs seconds rather than a second native compile.
FROM scratch AS sbom
COPY --from=build /src/service/target/sbom.json /sbom.json

# --- runtime --------------------------------------------------------------------------------------
# `mirror.dev.localhost:8080/redhat/…` is the same mirror, pointed at registry.access.redhat.com.
FROM mirror.dev.localhost:8080/redhat/ubi9/ubi-minimal:9.6
WORKDIR /work

# TWO PACKAGES, AND BOTH ARE LOAD-BEARING AT RUNTIME.
#
# `docker-ce-cli` is how this service reads anything at all: every host, swarm and node answer is a
# `docker … --format '{{json .}}'` child, and both terminals are a `docker run -it` / `docker exec
# -it` child on a pseudo-terminal. It comes from Docker's own repository because no UBI repository
# carries a docker-compatible client, and the repo definition is removed again afterwards so the
# shipped image does not carry a third-party package source it has no reason to consult. The
# identical block is in qits-containers', qits-ci's and qits-deployments' Dockerfiles — the other
# services that shell docker — and they should keep matching.
#
# NO `docker-buildx-plugin` and no compose plugin: this service builds no image and runs no stack.
# The vocabulary is reads plus `run`, `exec` and `rm -f` of its own labelled terminal containers.
#
# `util-linux-core` is for `setsid --ctty`, and without it there is no terminal at all. The child
# that runs on the PTY is launched as `sh -c 'exec 0<>"$0" …' <slave> setsid --ctty docker exec …`
# so that the CHILD claims the terminal for its own new session — the PID-1 SIGHUP incident fix
# qits-projects paid for and this service inherited (see terminal/TerminalProcesses). Without
# setsid the docker CLI runs with no controlling terminal: `-t` allocates one on the far side but
# SIGWINCH never reaches the CLI, so a browser resize stops resizing the remote tty.
# --nodocs and no weak deps because an image is not a workstation.
RUN printf '[docker-ce-stable]\nname=Docker CE Stable\nbaseurl=https://download.docker.com/linux/centos/9/$basearch/stable\nenabled=1\ngpgcheck=1\ngpgkey=https://download.docker.com/linux/centos/gpg\n' \
      > /etc/yum.repos.d/docker-ce.repo \
 && microdnf -y --setopt=install_weak_deps=0 --nodocs install docker-ce-cli util-linux-core \
 && microdnf -y clean all \
 && rm -f /etc/yum.repos.d/docker-ce.repo

# curl, so the container answers a docker healthcheck — the deployer injects one at `docker run` and
# polls it through `docker inspect`, and the probe runs INSIDE the container. UBI minimal already
# supplies curl-minimal; assert it is present without installing the conflicting full curl package.
RUN curl --version >/dev/null

# `quarkus.package.output-name` names the binary `qits-platform-system` (no -runner suffix), so the
# process is identifiable in `ps`/`docker top` — which matters more here than elsewhere, because
# this service's own work is spawning `docker` children and its process tree gets read while
# debugging a host. That name is spelled a third time in service/pom.xml as failsafe's
# `native.image.path`; all three move together.
COPY --from=build --chown=1001:root /src/service/target/qits-platform-system /work/qits-platform-system
RUN chmod +x /work/qits-platform-system

# THE MOUNT POINT FOR THE CLI's OWN CONFIG, created here so it is OWNED. The deployment mounts a
# volume at /work/config and sets DOCKER_CONFIG to it, because uid 1001 has no HOME: without one the
# CLI writes its warning about an unwritable config onto the user's terminal, in the middle of
# glances. The volume also carries the registry + mirror credential the glances pull needs — the
# edge grants no anonymous reads. Docker seeds a fresh named volume from whatever the image has at
# the mount path, ownership included; with no such directory it makes one root-owned 0755 and the
# CLI cannot write there at all. Group-writable and group-root because a ubi image is meant to
# survive being assigned an arbitrary uid with gid 0.
RUN mkdir -p /work/config && chown 1001:root /work/config && chmod 775 /work/config

# NO MOUNT POINT FOR DATA. This service stores nothing: every answer is read live and every terminal
# dies with the process.

EXPOSE 8080
USER 1001

# Everything this service serves hangs off /system — the client at /system/, the API and the terminal
# socket at /system/api/* and health, openapi and swagger-ui at /system/q/* — because qits-gateway
# routes verbatim by prefix. The paths are in application.properties, not here; see README.md.
ENTRYPOINT ["/work/qits-platform-system", "-Dquarkus.http.host=0.0.0.0"]
