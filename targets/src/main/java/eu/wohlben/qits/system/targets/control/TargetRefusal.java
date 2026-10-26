package eu.wohlben.qits.system.targets.control;

import eu.wohlben.qits.system.error.SystemException;

/**
 * Every refusal of the targets doors: a status, and the code a client branches on where there is
 * one. It travels through the service's one {@code SystemExceptionMapper}, so it answers {@code
 * {"code": …, "message": …}} like every other refusal here.
 */
public class TargetRefusal extends SystemException {

  private static final long serialVersionUID = 1L;

  public static final String MINIMUMS_EXCEED_TOTAL = "MINIMUMS_EXCEED_TOTAL";
  public static final String TARGET_BUSY = "TARGET_BUSY";
  public static final String TARGET_UNAVAILABLE = "TARGET_UNAVAILABLE";
  public static final String LEASE_EXISTS = "LEASE_EXISTS";
  public static final String SLOT_TYPE_NOT_YOURS = "SLOT_TYPE_NOT_YOURS";
  public static final String NOT_A_TENANT = "NOT_A_TENANT";
  public static final String NOT_YOUR_LEASE = "NOT_YOUR_LEASE";
  public static final String IMAGE_NOT_ALLOWED = "IMAGE_NOT_ALLOWED";
  public static final String ENV_KEY_NOT_ALLOWED = "ENV_KEY_NOT_ALLOWED";
  public static final String WORK_VOLUME_IN_USE = "WORK_VOLUME_IN_USE";
  public static final String TARGET_NAME_TAKEN = "TARGET_NAME_TAKEN";
  public static final String ALREADY_REGISTERED = "ALREADY_REGISTERED";
  public static final String RUNNER_PLANE_UNCONFIGURED = "RUNNER_PLANE_UNCONFIGURED";
  public static final String COMMISSIONING_OFF = "COMMISSIONING_OFF";
  public static final String IDP_REFUSED = "IDP_REFUSED";
  public static final String SIGN_IN_RUNNING = "SIGN_IN_RUNNING";
  public static final String SIGN_IN_NOT_FOUND = "SIGN_IN_NOT_FOUND";
  public static final String SIGN_IN_NOT_AWAITING_CODE = "SIGN_IN_NOT_AWAITING_CODE";

  /** The runner refused a docker request: its own container, or a malformed argument. */
  public static final String DOCKER_REFUSED = "DOCKER_REFUSED";

  /** Docker on the target could not answer (502). */
  public static final String DOCKER_FAILED = "DOCKER_FAILED";

  /** The runner did not answer a docker request in time (504). */
  public static final String DOCKER_TIMEOUT = "DOCKER_TIMEOUT";

  private final int status;
  private final String code;

  public TargetRefusal(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public static TargetRefusal badRequest(String message) {
    return new TargetRefusal(400, null, message);
  }

  public static TargetRefusal badRequest(String code, String message) {
    return new TargetRefusal(400, code, message);
  }

  public static TargetRefusal notFound(String message) {
    return new TargetRefusal(404, null, message);
  }

  public static TargetRefusal forbidden(String code, String message) {
    return new TargetRefusal(403, code, message);
  }

  public static TargetRefusal conflict(String code, String message) {
    return new TargetRefusal(409, code, message);
  }

  @Override
  public int status() {
    return status;
  }

  @Override
  public String code() {
    return code;
  }
}
