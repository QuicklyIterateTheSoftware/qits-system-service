package eu.wohlben.qits.system.targets.control;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.system.targets.entity.TargetAudit;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/**
 * The {@code target_audit} rows: who did what to a target or a lease beyond its owner's own doors.
 * Every row is written in a transaction of its own ({@code requiringNew}), never the caller's, so a
 * refused or failed call is recorded as well as one that changed something.
 *
 * <p>A row that could not be written is logged and dropped: by then the change it describes has
 * committed (or been refused), and failing the caller over its record would misreport what happened.
 */
@ApplicationScoped
public class TargetAuditLog {

  private static final Logger LOG = Logger.getLogger(TargetAuditLog.class);

  /** The actor of what this service does by itself (a deadline, a reconcile). */
  public static final String SYSTEM_ACTOR = "qits-system";

  public static final String LEASE_END = "LEASE_END";
  public static final String LEASE_ENDING_DEADLINE = "LEASE_ENDING_DEADLINE";

  /** The most rows one read answers. */
  public static final int MAX_READ = 500;

  /**
   * One row to write. {@code roles} is the caller's {@code qits:*} groups comma-joined ({@link
   * #roles}); {@code args} is any JSON, or null.
   */
  public record Entry(
      String actor,
      String roles,
      UUID targetId,
      UUID leaseId,
      String operation,
      String resource,
      JsonNode args,
      String outcome,
      String detail) {}

  /** Writes {@code entry} at {@code at} in its own transaction; never throws. */
  public void record(Entry entry, Instant at) {
    try {
      QuarkusTransaction.requiringNew()
          .run(
              () -> {
                TargetAudit row = new TargetAudit();
                row.id = UUID.randomUUID();
                row.at = at;
                row.actor = entry.actor() == null ? "(anonymous)" : entry.actor();
                row.roles = entry.roles() == null ? "" : entry.roles();
                row.targetId = entry.targetId();
                row.leaseId = entry.leaseId();
                row.operation = entry.operation();
                row.resource = entry.resource();
                row.args = entry.args() == null ? null : LeaseSpecs.text(entry.args());
                row.outcome = entry.outcome();
                row.detail = entry.detail();
                row.persist();
              });
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not record %s %s by %s on lease %s: %s",
          entry.operation(), entry.outcome(), entry.actor(), entry.leaseId(), e.toString());
    }
  }

  /** {@link #record(Entry, Instant)} now. */
  public void record(Entry entry) {
    record(entry, Instant.now());
  }

  /** A target's rows, newest first, at most {@code limit} (capped at {@link #MAX_READ}). */
  public List<TargetAudit> ofTarget(UUID targetId, int limit) {
    int bounded = Math.max(1, Math.min(limit, MAX_READ));
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                TargetAudit.<TargetAudit>find("targetId = ?1 order by at desc, id", targetId)
                    .page(0, bounded)
                    .list());
  }

  /** The caller's {@code qits:*} groups, sorted and comma-joined. */
  public static String roles(SecurityIdentity identity) {
    if (identity == null || identity.isAnonymous()) {
      return "";
    }
    return identity.getRoles().stream()
        .filter(role -> role.startsWith("qits:"))
        .sorted()
        .collect(Collectors.joining(","));
  }
}
