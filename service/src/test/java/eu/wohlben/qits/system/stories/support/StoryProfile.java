package eu.wohlben.qits.system.stories.support;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.system.api.PackagedSurfaceIT;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>One profile for the whole story catalogue</b>, and that is the point of it: every {@code
 * @QuarkusIntegrationTest} carrying this class shares a single launched fast-jar, a single boot, a
 * single terminal registry and a single recording of what was asked of docker. A second profile
 * would be a second process with a second startup, whose traffic would land in whichever diagram
 * happened to be open.
 *
 * <p>It extends {@link PackagedSurfaceIT.PackagedUnderTarget} rather than copying it. What a
 * launched qits-platform-system needs in order to boot at all is one answer, written out at length
 * over there, and a second copy of it would be a second place for it to drift. What is added here
 * is only the seams the stories move.
 *
 * <p><b>Every key is a RUNTIME key.</b> A packaged process takes its configuration as {@code -D}
 * arguments on a jar that was already built, so a build-time key would be silently ignored and
 * these stories would prove the opposite of what they say.
 *
 * <p><b>Two stand-ins start before the application, and both park their coordinates in system
 * properties</b> — the one namespace every classloader in a JVM shares, which matters because a
 * test profile is instantiated in more than one of them and the launched artifact is a different
 * process again. {@link MockIdp#ensureStarted()} is the issuer; {@link StoryDocker#install()} is
 * the docker binary, staged into a directory only the launched process ever writes to.
 *
 * <p><b>What is deliberately NOT here.</b> There is no telemetry key and no event-bus key to
 * darken: this service has no datasource, no qits-eventstream and no opentelemetry extension on its
 * classpath. The only two things a boot here would dial out for are the idp, pointed at {@link
 * MockIdp} below, and the glances image pull, which the inherited {@code
 * qits.system.glances.pull-at-startup=false} switches off at its own source. The boot sweep's
 * remaining two calls go to the stand-in binary like every other docker call, and the first story
 * in the catalogue claims them.
 *
 * <p><b>The linger timers keep their shipped values, and that is a stated gap.</b> {@code
 * terminals.linger} is 60 seconds and {@code terminals.glances-linger} 3 — the unit suite shortens
 * both in {@code src/test/resources/application.properties}, and nothing here does. A story that
 * waited out a linger window would be a story indistinguishable from a story that hung, and a
 * terminal reaped by a timer would put a {@code docker rm -f} in whichever diagram was open when it
 * fired. So every terminal story ends its own session explicitly, and the reaper's own coverage
 * stays where it already is: {@code TerminalLingerTest}.
 */
public class StoryProfile extends PackagedSurfaceIT.PackagedUnderTarget {

  /**
   * The audience this service enforces, and it is the PLATFORM's one audience rather than this
   * service's name: qits-platform-idp stamps {@code qits-platform} on every token it mints, so a
   * token carrying it is any platform caller's and roles are what decide what that caller may do.
   *
   * <p>It is spelled here as a literal because it is spelled as a literal where it ships — {@code
   * quarkus.oidc.token.audience=qits-platform} in {@code application.properties}, with no
   * expression to resolve — so the audience under test is the shipped one.
   */
  public static final String AUDIENCE = "qits-platform";

  @Override
  public Map<String, String> getConfigOverrides() {
    MockIdp idp = MockIdp.ensureStarted();
    Map<String, String> overrides = new LinkedHashMap<>(super.getConfigOverrides());
    // THE GATE, and turning it on is the point: the shipped tenant is
    // quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}, so this one key is the
    // difference between a service that validates machine bearers and one that does not. No other
    // suite in this repository turns it on at all, which is why the whole quarkus.oidc.* block —
    // the boot-time JWKS fetch, audience enforcement, groups→roles mapping — runs nowhere else.
    //
    // It leaves a PERSON's door exactly where it was: the tenant is bearer-only, so a request
    // carrying no Authorization header is never challenged by it and falls through to
    // qits-auth-core's X-Qits-User mechanism, which is how an operator reaches this service behind
    // the platform edge. Both doors are open here, which is what lets one catalogue tell the
    // machine's ceiling from the person's.
    overrides.put("qits.auth.machine.required", "true");
    // Where the idp is. Runtime key, so the packaged artifact is otherwise exactly what ships —
    // discovery stays off and jwks-path stays `jwks`, joined onto this URL.
    overrides.put("quarkus.oidc.auth-server-url", idp.baseUrl());
    // THE CATALOGUE'S OWN DOCKER. The parent points this at the script under target/test-classes,
    // which the whole surefire suite has already written several hundred calls into by the time
    // failsafe starts; this is the same script staged into a directory the launched process is the
    // only writer of. See StoryDocker: it is what makes the docker hop evidence rather than a claim.
    overrides.put("qits.system.docker.binary", StoryDocker.install());
    return Map.copyOf(overrides);
  }
}
