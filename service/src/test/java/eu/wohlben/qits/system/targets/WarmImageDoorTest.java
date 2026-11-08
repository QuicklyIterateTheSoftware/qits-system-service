package eu.wohlben.qits.system.targets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.system.targets.control.WarmImages;
import eu.wohlben.qits.system.targets.entity.SlotType;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /system/api/targets/warm-images} (qits-1955): every {@code warm_image} row, in slot
 * type order, as {@link WarmImages} holds it; a literal path that a target lookup never answers.
 */
@QuarkusTest
class WarmImageDoorTest {

  private static final String API = "/system/api/targets/warm-images";

  private static final String WORKSPACE = "registry.qits.wohlben.eu/qits/workspace";

  private static final String CI = "registry.qits.wohlben.eu/qits/build-images/ci-slot";

  @Inject WarmImages warmImages;

  @Inject ContractBearers bearers;

  @BeforeEach
  void clean() {
    TargetRows.clear();
    bearers.reset();
  }

  @AfterEach
  void disarm() {
    TargetRows.clear();
    bearers.reset();
  }

  private static RequestSpecification as(String user, String roles) {
    return given().header("Authorization", ContractBearers.bearer(user, roles));
  }

  @Test
  void anEmptyStoreAnswersAnEmptyListNotATargetLookup() {
    as("an-operator", "qits:admin").get(API).then().statusCode(200).body("$", hasSize(0));
  }

  @Test
  void aReleasedSlotImageIsListedWithEveryField() {
    Instant before = Instant.now();
    assertEquals(Set.of(SlotType.WORKSPACE), warmImages.released("qits/workspace", "2026.1027.193351"));
    assertEquals(
        Set.of(SlotType.CI), warmImages.released("qits/build-images/ci-slot", "2026.1027.1"));

    var answer = as("dyn-agent", "qits:agent").get(API).then().statusCode(200);
    answer
        .body("$", hasSize(2))
        .body("[0].slotType", equalTo("CI"))
        .body("[0].packageName", equalTo("qits/build-images/ci-slot"))
        .body("[0].version", equalTo("2026.1027.1"))
        .body("[0].image", equalTo(CI + ":2026.1027.1"))
        .body("[1].slotType", equalTo("WORKSPACE"))
        .body("[1].packageName", equalTo("qits/workspace"))
        .body("[1].version", equalTo("2026.1027.193351"))
        .body("[1].image", equalTo(WORKSPACE + ":2026.1027.193351"))
        .body("[1].updatedAt", notNullValue());
    Instant updatedAt = Instant.parse(answer.extract().path("[1].updatedAt"));
    assertTrue(!updatedAt.isBefore(before.minusSeconds(1)), updatedAt + " before " + before);
  }

  @Test
  void everyListedReaderIsAdmittedAndNoOtherRole() {
    for (String role :
        new String[] {"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:system", "qits:agent"}) {
      as("a-reader", role).get(API).then().statusCode(200);
    }
    as("a-desk", "qits:desk").get(API).then().statusCode(403);
    as("a-runner", "qits:target-runner-registration").get(API).then().statusCode(403);
  }
}
