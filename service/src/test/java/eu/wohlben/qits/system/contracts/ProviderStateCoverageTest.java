package eu.wohlben.qits.system.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.archrules.ProviderStateCoverageRules;
import org.junit.jupiter.api.Test;

/**
 * Every state recorded in golden-masters/index.json has its {@code @State} callback on {@link
 * ConsumerPactVerificationTest}, so a missing one fails here instead of in a consumer's pin
 * (qits-1754).
 */
class ProviderStateCoverageTest {

  @Test
  void everyRecordedStateHasItsCallback() {
    ProviderStateCoverageRules.assertCovered(ConsumerPactVerificationTest.class);
  }

  /**
   * {@link ProviderStates#A_TARGET_WITH_A_LIVE_REGISTRATION_SUBJECT} repeats {@link
   * ProviderStates#TARGET_ID} and {@link ProviderStates#REGISTRATION_SUBJECT} as a literal (a
   * {@code @State} value must be a compile-time constant, so it cannot reference them) — this
   * catches the two drifting apart.
   */
  @Test
  void theLiveRegistrationSubjectStateNameMatchesItsFrozenValues() {
    assertEquals(
        "target "
            + ProviderStates.TARGET_ID
            + " has registration subject "
            + ProviderStates.REGISTRATION_SUBJECT,
        ProviderStates.A_TARGET_WITH_A_LIVE_REGISTRATION_SUBJECT);
  }
}
