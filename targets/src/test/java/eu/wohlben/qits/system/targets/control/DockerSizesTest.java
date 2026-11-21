package eu.wohlben.qits.system.targets.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** {@link DockerSizes}: docker's units, as go-units' {@code RAMInBytes} reads them. */
class DockerSizesTest {

  private static final long K = 1024L;
  private static final long M = K * 1024;
  private static final long G = M * 1024;

  @Test
  void plainBytesAndEverySuffixInAnyCase() {
    assertEquals(OptionalLong.of(1536), DockerSizes.parse("1536"));
    assertEquals(OptionalLong.of(512), DockerSizes.parse("512b"));
    assertEquals(OptionalLong.of(512), DockerSizes.parse("512B"));
    assertEquals(OptionalLong.of(4 * K), DockerSizes.parse("4k"));
    assertEquals(OptionalLong.of(4 * K), DockerSizes.parse("4K"));
    assertEquals(OptionalLong.of(512 * M), DockerSizes.parse("512m"));
    assertEquals(OptionalLong.of(512 * M), DockerSizes.parse("512M"));
    assertEquals(OptionalLong.of(6 * G), DockerSizes.parse("6g"));
    assertEquals(OptionalLong.of(6 * G), DockerSizes.parse("6G"));
    assertEquals(OptionalLong.of(6 * G), DockerSizes.parse("6gb"));
    assertEquals(OptionalLong.of(6 * G), DockerSizes.parse("6GiB"));
    assertEquals(OptionalLong.of(6 * G), DockerSizes.parse(" 6g "));
    assertEquals(OptionalLong.of(1536 * M), DockerSizes.parse("1.5g"));
    assertEquals(OptionalLong.of(2 * K * G), DockerSizes.parse("2t"));
  }

  @Test
  void whatDockerWouldRefuseIsEmpty() {
    for (String text : new String[] {null, "", "  ", "g", "-1g", "6x", "6 gigs", "6gg", "0x10"}) {
      assertTrue(DockerSizes.parse(text).isEmpty(), String.valueOf(text));
    }
    assertTrue(DockerSizes.parse("99999999999p").isEmpty(), "past a long");
  }

  @Test
  void aLeaseIsChargedItsMemoryLimitAlone() throws Exception {
    ObjectMapper json = new ObjectMapper();
    assertEquals(
        6 * G,
        DockerSizes.charge(
            json.readTree(
                "{\"image\":\"x\",\"limits\":{\"memory\":\"6g\",\"memorySwap\":\"8g\"}}")));
    assertEquals(
        6 * G,
        DockerSizes.charge("{\"image\":\"x\",\"limits\":{\"memory\":\"6g\",\"pidsLimit\":4096}}"));
    assertEquals(1024, DockerSizes.charge("{\"limits\":{\"memory\":1024}}"));
    assertEquals(0, DockerSizes.charge("{\"image\":\"x\"}"));
    assertEquals(0, DockerSizes.charge("{\"limits\":{\"memorySwap\":\"8g\"}}"));
    assertEquals(0, DockerSizes.charge("{\"limits\":{\"memory\":\"lots\"}}"));
    assertEquals(0, DockerSizes.charge("{\"limits\":{\"memory\":null}}"));
    assertEquals(0, DockerSizes.charge((String) null));
    assertEquals(0, DockerSizes.charge("not json"));
  }

  @Test
  void theShortestSpelling() {
    assertEquals("6g", DockerSizes.format(6 * G));
    assertEquals("512m", DockerSizes.format(512 * M));
    assertEquals("1536m", DockerSizes.format(1536 * M));
    assertEquals("1000b", DockerSizes.format(1000));
    assertEquals("0b", DockerSizes.format(0));
    assertEquals("2t", DockerSizes.format(2 * K * G));
  }
}
