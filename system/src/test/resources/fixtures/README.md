# Fixtures — real docker output, captured

Every file here is what a real docker CLI printed, captured from the platform host (docker 29.7.2,
a one-node swarm) on 2026-08-23. They are the whole point of the parser tests: docker's `ls`
columns and its `inspect` documents are different shapes with different field names, both change
between releases, and a parser written from the API docs rather than from the output is a parser
that returns nulls.

`.jsonl` is what `--format '{{json .}}'` prints: one JSON object per LINE, not an array.
`.json` is what `inspect` prints: an array of documents.

Two files are HAND-WRITTEN rather than captured, and say so here rather than pretending otherwise:
`docker-config-ls.jsonl` / `docker-config-inspect.json` / `docker-secret-ls.jsonl`. The capture host
holds no swarm configs or secrets, so these follow docker's documented shapes (the `Spec.Data`
base64 in a config inspect, the `Driver` column a secret list carries and a config list does not).
Replace them with a real capture the first time this runs against a platform that has some.

One part of a captured file is hand-added, for the same reason: the captured
`docker-service-inspect.json` mounted nothing, so its `Spec.TaskTemplate.ContainerSpec.Mounts` — a
volume and a read-only bind (qits-2345) — follow docker's documented `Mount` shape (`Type`, `Source`,
`Target`, `ReadOnly` omitted when false, `VolumeOptions` on a volume). Replace them with a capture of
a service that mounts something.

**Environment values are scrubbed.** `docker-container-inspect.json` and
`docker-service-inspect.json` are real captures of live platform services, whose environment carried
real database passwords and idp client secrets. Every value under a key matching
PASSWORD/SECRET/TOKEN/CREDENTIAL was replaced with `not-a-real-secret-scrubbed-for-the-fixture` —
which the parser tests then assert never appears in a parsed record, because this service returns
environment KEYS and never values.
