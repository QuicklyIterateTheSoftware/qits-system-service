package eu.wohlben.qits.system.targets.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.protocol.health.CheckResult;
import eu.wohlben.qits.system.targets.entity.LoginValue;
import eu.wohlben.qits.system.targets.entity.Target;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The {@code dialback} check (qits-1809): it fails while a target's CI leases have never dialled
 * back {@code threshold} times in a row and the newest is within the cool-off, and it gates.
 */
class TargetHealthDialbackTest {

  private static final Instant FAILED_AT = Instant.parse("2026-11-02T06:20:00Z");
  private static final Duration COOLOFF = Duration.ofMinutes(30);
  private static final int THRESHOLD = 3;

  private static Target row(int failures, Instant failedAt) {
    Target row = new Target();
    row.name = "workstation";
    row.slotsTotal = 6;
    row.loginClaude = LoginValue.UNKNOWN;
    row.loginKimi = LoginValue.UNKNOWN;
    row.dialbackFailures = failures;
    row.dialbackFailedAt = failedAt;
    return row;
  }

  private static CheckResult dialback(Target row, Instant now) {
    return TargetHealth.serverChecks(row, THRESHOLD, COOLOFF, now).stream()
        .filter(c -> TargetHealth.DIALBACK.equals(c.name()))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void itFailsDuringTheCoolOffAndSaysHowManyAndUntilWhen() {
    CheckResult check = dialback(row(3, FAILED_AT), FAILED_AT.plus(Duration.ofMinutes(29)));
    assertFalse(check.ok());
    assertTrue(check.detail().startsWith("3 CI lease(s) in a row never dialled back"), check.detail());
    assertTrue(check.detail().endsWith("until 2026-11-02T06:50:00Z"), check.detail());
    assertEquals("2026-11-02T06:50:00Z", check.data().get("until"));
  }

  @Test
  void itPassesOnceTheCoolOffHasPassed() {
    CheckResult check = dialback(row(3, FAILED_AT), FAILED_AT.plus(COOLOFF));
    assertTrue(check.ok());
    assertNull(check.detail());
  }

  @Test
  void itPassesBelowTheThreshold() {
    assertTrue(dialback(row(2, FAILED_AT), FAILED_AT).ok());
    assertTrue(dialback(row(0, null), FAILED_AT).ok());
  }

  @Test
  void itGatesUnlikeLoginAndSlots() {
    List<CheckResult> checks = new ArrayList<>();
    checks.add(new CheckResult("docker", true, null, null));
    checks.addAll(TargetHealth.serverChecks(row(4, FAILED_AT), THRESHOLD, COOLOFF, FAILED_AT));
    TargetHealth.Verdict verdict = TargetHealth.verdict(checks, true, null, false);
    assertFalse(verdict.ok());
    assertFalse(verdict.admitted(), "dialback is not REPORT_ONLY");
    assertTrue(verdict.gateDetail().startsWith("dialback: 4 CI lease(s)"), verdict.gateDetail());

    List<CheckResult> after = new ArrayList<>();
    after.add(new CheckResult("docker", true, null, null));
    after.addAll(
        TargetHealth.serverChecks(row(4, FAILED_AT), THRESHOLD, COOLOFF, FAILED_AT.plus(COOLOFF)));
    assertTrue(TargetHealth.verdict(after, true, null, false).admitted());
  }
}
