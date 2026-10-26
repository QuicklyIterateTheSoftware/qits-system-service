package eu.wohlben.qits.system.targets.api;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.system.targets.control.OwnerIdentity;
import eu.wohlben.qits.system.targets.control.TargetAuditLog;
import eu.wohlben.qits.system.targets.control.TargetRefusal;
import eu.wohlben.qits.system.targets.control.Targets;
import eu.wohlben.qits.system.targets.entity.Target;
import eu.wohlben.qits.system.targets.runner.TargetDocker;
import eu.wohlben.qits.systemrunner.protocol.Docker;
import eu.wohlben.qits.systemrunner.protocol.DockerOp;
import eu.wohlben.qits.systemrunner.protocol.DockerResult;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;

/**
 * What every Docker API door does around its one {@link TargetDocker} exchange: resolves the target
 * the way the caller wrote it (a UUID or a name), writes the call's {@code target_audit} row
 * <b>before</b> it answers — {@code DOCKER_<OP>}, the container or volume, {@code {tail, since,
 * argv}} and the outcome — and turns an answer that is not {@code OK} into the refusal the dossier
 * names:
 *
 * <pre>
 *   OK 200 · NOT_FOUND 404 · REFUSED 403 DOCKER_REFUSED · FAILED 502 DOCKER_FAILED
 *   TIMEOUT 504 DOCKER_TIMEOUT · NOT_CONNECTED 409 TARGET_UNAVAILABLE
 * </pre>
 *
 * An unknown target is a 404 and a {@code NOT_FOUND} row with no target id.
 */
@ApplicationScoped
public class DockerDoor {

  static final String TIMEOUT = "TIMEOUT";
  static final String NOT_CONNECTED = "NOT_CONNECTED";
  static final String NOT_FOUND = "NOT_FOUND";
  static final String BAD_REQUEST = "BAD_REQUEST";

  /** The most of a non-OK answer's body a row's detail keeps. */
  static final int DETAIL_MAX = 1000;

  @Inject TargetDocker docker;

  @Inject Targets targets;

  @Inject TargetAuditLog audit;

  @Inject OwnerIdentity owners;

  @Inject SecurityIdentity identity;

  /**
   * One call as its audit row describes it.
   *
   * @param targetId null until the target is resolved
   * @param leaseId the lease of {@code GET /leases/{id}/logs}; else null
   * @param resource the container or volume; null for a listing
   */
  record Call(
      UUID targetId,
      UUID leaseId,
      DockerOp op,
      String resource,
      Integer tail,
      String since,
      List<String> argv,
      Integer timeoutSeconds) {

    static Call of(DockerOp op, String resource) {
      return new Call(null, null, op, resource, null, null, null, null);
    }

    Call on(UUID target) {
      return new Call(target, leaseId, op, resource, tail, since, argv, timeoutSeconds);
    }

    Call naming(String name) {
      return new Call(targetId, leaseId, op, name, tail, since, argv, timeoutSeconds);
    }

    Docker request() {
      return new Docker(UUID.randomUUID(), op, resource, tail, since, argv, timeoutSeconds);
    }
  }

  /** The target {@code id} names (a UUID or a name), or a 404 with its audit row. */
  Target target(String id, Call call) {
    Target target = find(id);
    if (target == null) {
      String message = "No target " + id;
      record(call, NOT_FOUND, message);
      throw TargetRefusal.notFound(message);
    }
    return target;
  }

  /** The target {@code id} names (a UUID or a name), or a 404 with no audit row: for reads. */
  Target resolve(String id) {
    Target target = find(id);
    if (target == null) {
      throw TargetRefusal.notFound("No target " + id);
    }
    return target;
  }

  private Target find(String id) {
    if (id == null || id.isBlank()) {
      return null;
    }
    try {
      return targets.find(UUID.fromString(id));
    } catch (IllegalArgumentException notAUuid) {
      return targets.findByName(id);
    }
  }

  /** Runs {@code call} on {@code target}, audits it, and answers its OK result or throws. */
  DockerResult run(Target target, Call call) {
    return settle(target, call.on(target.id), exchange(target, call.on(target.id)));
  }

  /** Runs {@code call} on {@code target} without auditing it: one step of a door that audits once. */
  TargetDocker.Outcome exchange(Target target, Call call) {
    return docker.call(target.id, call.request());
  }

  /** Audits {@code outcome} as {@code call}, and answers its OK result or throws its refusal. */
  DockerResult settle(Target target, Call call, TargetDocker.Outcome outcome) {
    switch (outcome) {
      case TargetDocker.NotConnected ignored -> {
        String message = "target " + target.name + " is not connected";
        record(call, NOT_CONNECTED, message);
        throw TargetRefusal.conflict(TargetRefusal.TARGET_UNAVAILABLE, message);
      }
      case TargetDocker.TimedOut late -> {
        String message =
            "target " + target.name + " did not answer docker " + call.op() + " within "
                + late.waited().toSeconds() + " s";
        record(call, TIMEOUT, message);
        throw new TargetRefusal(504, TargetRefusal.DOCKER_TIMEOUT, message);
      }
      case TargetDocker.Answered answered -> {
        DockerResult result = answered.result();
        String status = result.status().name();
        switch (result.status()) {
          case OK -> {
            record(call, status, result.exitCode() == null ? null : "exit " + result.exitCode());
            return result;
          }
          case NOT_FOUND -> {
            record(call, status, result.body());
            throw TargetRefusal.notFound(orDefault(result.body(), "No " + call.resource()));
          }
          case REFUSED -> {
            record(call, status, result.body());
            throw TargetRefusal.forbidden(
                TargetRefusal.DOCKER_REFUSED, orDefault(result.body(), "Refused by the runner"));
          }
          case FAILED -> {
            record(call, status, result.body());
            throw new TargetRefusal(
                502, TargetRefusal.DOCKER_FAILED, orDefault(result.body(), "Docker failed"));
          }
        }
        throw new IllegalStateException("unreachable");
      }
    }
  }

  /** A refusal of the request itself, before anything is sent: audited, then a 400. */
  TargetRefusal badRequest(Call call, String message) {
    record(call, BAD_REQUEST, message);
    return TargetRefusal.badRequest(message);
  }

  /** Writes {@code call}'s row with {@code outcome}. Never throws ({@link TargetAuditLog}). */
  void record(Call call, String outcome, String detail) {
    audit.record(
        new TargetAuditLog.Entry(
            owners.clientId(identity),
            TargetAuditLog.roles(identity),
            call.targetId(),
            call.leaseId(),
            "DOCKER_" + call.op().name(),
            call.resource(),
            args(call),
            outcome,
            detail == null || detail.length() <= DETAIL_MAX ? detail : detail.substring(0, DETAIL_MAX)));
  }

  /** {@code {tail, since, argv, timeoutSeconds}}, the set members only; null when none is. */
  static ObjectNode args(Call call) {
    ObjectNode args = JsonNodeFactory.instance.objectNode();
    if (call.tail() != null) {
      args.put("tail", call.tail());
    }
    if (call.since() != null) {
      args.put("since", call.since());
    }
    if (call.argv() != null) {
      ArrayNode argv = args.putArray("argv");
      call.argv().forEach(argv::add);
    }
    if (call.timeoutSeconds() != null) {
      args.put("timeoutSeconds", call.timeoutSeconds());
    }
    return args.isEmpty() ? null : args;
  }

  private static String orDefault(String body, String fallback) {
    return body == null || body.isBlank() ? fallback : body;
  }
}
