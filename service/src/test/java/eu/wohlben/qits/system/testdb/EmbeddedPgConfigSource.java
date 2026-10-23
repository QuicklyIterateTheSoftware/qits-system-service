package eu.wohlben.qits.system.testdb;

import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Hands the running {@link EmbeddedPg} to every {@code @QuarkusTest} in this module, as the keys a
 * deployment would supply, for <b>both</b> datasources this deployable opens: {@code system}, the
 * targets module's store, and {@code eventstream}, the outbox's, declared in the qits-eventstream
 * jar. The bus is dark in {@code %test}, which stops publishing — not the datasource: Quarkus opens
 * it and runs Flyway at boot regardless.
 *
 * <p>A config source rather than lines in {@code application.properties} because the port is
 * chosen at run time; the ordinal sits above application.properties so this wins over both jars'
 * unresolvable {@code ${QITS_RESOURCE_*}} defaults. Copied from qits-workspaces-service.
 */
public class EmbeddedPgConfigSource implements ConfigSource {

  private static final String SYSTEM_DATABASE = "qits_system_service_test";

  private static final String EVENTSTREAM_DATABASE = "qits_system_eventstream_service_test";

  private final Map<String, String> values =
      Map.of(
          "quarkus.datasource.system.jdbc.url", EmbeddedPg.url(SYSTEM_DATABASE),
          "quarkus.datasource.system.username", EmbeddedPg.USER,
          "quarkus.datasource.system.password", EmbeddedPg.PASSWORD,
          "quarkus.datasource.eventstream.jdbc.url", EmbeddedPg.url(EVENTSTREAM_DATABASE),
          "quarkus.datasource.eventstream.username", EmbeddedPg.USER,
          "quarkus.datasource.eventstream.password", EmbeddedPg.PASSWORD);

  @Override
  public int getOrdinal() {
    return 500;
  }

  @Override
  public Set<String> getPropertyNames() {
    return values.keySet();
  }

  @Override
  public String getValue(String propertyName) {
    return values.get(propertyName);
  }

  @Override
  public String getName() {
    return "embedded-pg";
  }
}
