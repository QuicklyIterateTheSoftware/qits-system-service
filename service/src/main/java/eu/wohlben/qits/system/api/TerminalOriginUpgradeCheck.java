package eu.wohlben.qits.system.api;

import io.quarkus.websockets.next.HttpUpgradeCheck;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Refuses a WebSocket handshake that came from another site.
 *
 * <p><b>Why a check of our own.</b> The same-origin policy does not apply to WebSockets: a page on
 * any origin may open one to this service, and the browser will attach the operator's cookies to
 * the handshake. Every other route here is protected by the fact that a cross-site {@code fetch}
 * cannot read the answer; a socket has no such protection, because the attacker's page reads the
 * frames directly. So a terminal reachable from any origin is a shell on the platform host handed
 * to whichever site an operator happened to visit while logged in. This is the cross-site WebSocket
 * hijacking guard for a root-equivalent shell, and it is why a foreign origin stays refused however
 * the platform's own pages move.
 *
 * <p><b>The rule</b> admits a handshake on any one of three grounds, and refuses everything else
 * with a 403:
 *
 * <ol>
 *   <li><b>No {@code Origin}.</b> Browsers always send one, so the absence means a non-browser
 *       client — a machine, a test, {@code websocat} — and those are not subject to the attack this
 *       exists to stop (nobody's ambient credential is being borrowed).
 *   <li><b>The origin's host is the host this request was addressed to.</b> Behind the edge that is
 *       the first {@code X-Forwarded-Host} entry; direct, it is {@code Host}. The service's own
 *       client, served from {@code system.qits.<domain>}, dials its socket on that same name, so the
 *       origin and the forwarded host agree.
 *   <li><b>The origin is one of the platform's own pages</b> (qits-2356): scheme exactly {@code
 *       https}, no port, no path, no userinfo, and a host that is the anchor {@code
 *       qits.system.terminals.origin-domain} ({@code qits.<domain>}) itself or any name ending in
 *       {@code .qits.<domain>}, at any depth, compared without case. This is what admits the
 *       landing app, whose apex {@code https://qits.<domain>} dials {@code
 *       wss://system.qits.<domain>/system/api/terminals/<id>} — a cross-host dial that rule 2 alone
 *       refuses. It is the same anchor the edge's CORS is held to ({@code EdgeCors}, {@code
 *       EdgeSessions.admitsOrigin} in qits-edge): every name under the domain is the platform's
 *       own, so a page there is no stranger than this service's own client. A suffix match on the
 *       DOT-prefixed anchor is what keeps {@code xqits.<domain>} and {@code
 *       qits.<domain>.evil.example} out; requiring {@code https} and the default port is what
 *       keeps a plaintext or a side-port listener on a platform name out.
 * </ol>
 *
 * <p>{@code appliesTo} is not narrowed: this service has one socket, and a check that had to be
 * remembered when a second is added is a check that will be forgotten.
 */
@ApplicationScoped
public class TerminalOriginUpgradeCheck implements HttpUpgradeCheck {

  private static final Logger LOG = Logger.getLogger(TerminalOriginUpgradeCheck.class);

  /** What a refused handshake answers. Not 401: the credential was fine, the origin was not. */
  private static final int FORBIDDEN = 403;

  /** The platform's own domain anchor, {@code qits.<domain>}: it and every name under it. */
  @ConfigProperty(name = "qits.system.terminals.origin-domain")
  String originDomain;

  @Override
  public Uni<CheckResult> perform(HttpUpgradeContext context) {
    String origin = context.httpRequest().getHeader("Origin");
    if (origin == null || origin.isBlank()) {
      return CheckResult.permitUpgrade();
    }
    String expected = expectedHost(context);
    String actual = hostOf(origin);
    if (expected != null && expected.equalsIgnoreCase(actual)) {
      return CheckResult.permitUpgrade();
    }
    if (underTheDomain(origin)) {
      return CheckResult.permitUpgrade();
    }
    LOG.warnf(
        "Refusing a terminal handshake from origin %s; this service answers as %s", origin, expected);
    return CheckResult.rejectUpgrade(FORBIDDEN);
  }

  /**
   * Whether the origin is a platform page: {@code https}, no port, no userinfo, no path, and a host
   * that is the anchor or ends in {@code .<anchor>}. An origin that does not parse is nobody's.
   */
  boolean underTheDomain(String origin) {
    if (originDomain == null || originDomain.isBlank()) {
      return false;
    }
    URI uri;
    try {
      uri = new URI(origin.trim());
    } catch (URISyntaxException e) {
      return false;
    }
    String host = uri.getHost();
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || host == null
        || uri.getPort() != -1
        || uri.getRawUserInfo() != null
        || !host.equals(uri.getRawAuthority())
        || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null) {
      return false;
    }
    String name = host.toLowerCase(Locale.ROOT);
    String anchor = originDomain.trim().toLowerCase(Locale.ROOT);
    return name.equals(anchor) || name.endsWith("." + anchor);
  }

  /**
   * The host this request was addressed to. The gateway rewrites {@code Host} to the internal
   * service name, so its forwarded header is the one that matches what the browser typed — and it
   * may carry a list, of which the FIRST entry is the original client's.
   */
  private static String expectedHost(HttpUpgradeContext context) {
    String forwarded = context.httpRequest().getHeader("X-Forwarded-Host");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    return context.httpRequest().getHeader("Host");
  }

  /** The authority of an origin — {@code https://qits.example:8443} becomes {@code qits.example:8443}. */
  private static String hostOf(String origin) {
    String value = origin.trim();
    int scheme = value.indexOf("://");
    if (scheme >= 0) {
      value = value.substring(scheme + 3);
    }
    int slash = value.indexOf('/');
    return slash >= 0 ? value.substring(0, slash) : value;
  }
}
