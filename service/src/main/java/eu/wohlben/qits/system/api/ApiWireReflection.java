package eu.wohlben.qits.system.api;

import eu.wohlben.qits.system.reads.ConfigDetail;
import eu.wohlben.qits.system.reads.ConfigSummary;
import eu.wohlben.qits.system.reads.ContainerDetail;
import eu.wohlben.qits.system.reads.ContainerSummary;
import eu.wohlben.qits.system.reads.DiskUsage;
import eu.wohlben.qits.system.reads.HostInfo;
import eu.wohlben.qits.system.reads.ImageSummary;
import eu.wohlben.qits.system.reads.LogChunk;
import eu.wohlben.qits.system.reads.NetworkSummary;
import eu.wohlben.qits.system.reads.NodeDetail;
import eu.wohlben.qits.system.reads.NodeSummary;
import eu.wohlben.qits.system.reads.Overview;
import eu.wohlben.qits.system.reads.SecretSummary;
import eu.wohlben.qits.system.reads.ServiceDetail;
import eu.wohlben.qits.system.reads.ServiceSummary;
import eu.wohlben.qits.system.reads.SwarmInfo;
import eu.wohlben.qits.system.reads.TaskSummary;
import eu.wohlben.qits.system.reads.VolumeSummary;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Registers every type Jackson writes on this service's wire.
 *
 * <p><b>Why it is needed at all.</b> The native-image build discovers the types a JAX-RS method
 * DECLARES as its return type; it cannot see through a {@code Response.entity(...)}, which is how
 * the terminal POST answers 201-or-200 and how every error body goes out. A type it did not see is
 * a type with no serialiser in the binary — a 500 in production while the whole JVM suite stays
 * green.
 *
 * <p>The declared ones are listed here too. It costs nothing, and a list that is "the wire, plus
 * the ones that happen to need it" is a list somebody has to reason about before adding to it.
 *
 * <p><b>Nested records are NOT automatic.</b> {@code DiskUsage.Entry}, {@code
 * ServiceDetail.NetworkAttachment}/{@code Mount}, {@code ContainerDetail.Mount}/{@code
 * NetworkBinding} and {@code TerminalView.Container} each have their own entry: registering the
 * outer record does not reach the types of its components.
 *
 * <p>ADD A RESPONSE TYPE, ADD IT HERE, IN THE SAME COMMIT. {@code PackagedSurfaceIT} is what
 * catches a forgotten one, and only when run against the binary.
 */
@RegisterForReflection(
    targets = {
      Overview.class,
      HostInfo.class,
      SwarmInfo.class,
      DiskUsage.class,
      DiskUsage.Entry.class,
      NodeSummary.class,
      NodeDetail.class,
      ServiceSummary.class,
      ServiceDetail.class,
      ServiceDetail.NetworkAttachment.class,
      ServiceDetail.Mount.class,
      TaskSummary.class,
      ConfigSummary.class,
      ConfigDetail.class,
      SecretSummary.class,
      ContainerSummary.class,
      ContainerDetail.class,
      ContainerDetail.Mount.class,
      ContainerDetail.NetworkBinding.class,
      ImageSummary.class,
      VolumeSummary.class,
      NetworkSummary.class,
      LogChunk.class,
      TerminalRequest.class,
      TerminalView.class,
      TerminalView.Container.class,
      ErrorBody.class,
    })
public final class ApiWireReflection {

  private ApiWireReflection() {}
}
