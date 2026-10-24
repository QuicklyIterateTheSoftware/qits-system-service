package eu.wohlben.qits.system.targets.runner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.system.targets.runner.TargetCommissionReconcile.Credentials;
import org.junit.jupiter.api.Test;

/** Which {@code target-runner} clients a target row keeps alive. */
class TargetCommissionReconcileTest {

  @Test
  void aGoneTargetSparesNothing() {
    assertFalse(TargetCommissionReconcile.spares(null, "client-1"));
  }

  @Test
  void aRegisteredTargetSparesOnlyItsOwnClient() {
    Credentials row = new Credentials("client-1", null);
    assertTrue(TargetCommissionReconcile.spares(row, "client-1"));
    assertFalse(TargetCommissionReconcile.spares(row, "client-0"));
  }

  @Test
  void anUnregisteredTargetSparesEveryClientOfIt() {
    Credentials row = new Credentials(null, "tok-1");
    assertTrue(TargetCommissionReconcile.spares(row, "client-1"));
    assertTrue(TargetCommissionReconcile.spares(new Credentials(null, null), "client-1"));
  }

  @Test
  void aPendingRegistrationTokenSparesEveryClientOfItsTarget() {
    Credentials rotated = new Credentials("client-1", "tok-2");
    assertTrue(TargetCommissionReconcile.spares(rotated, "client-1"), "the running runner's");
    assertTrue(
        TargetCommissionReconcile.spares(rotated, "client-2"),
        "a re-registration's, commissioned before the row names it");
  }
}
