package eu.wohlben.qits.system.contracts;

import eu.wohlben.qits.system.targets.control.TargetsConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A {@link TargetsConfig} outside CDI, for a commissioner pointed at a pact's mock idp. */
record SuiteTargetsConfig(String idpUrl, String clientId, String secret) implements TargetsConfig {

  @Override
  public Map<String, String> tenants() {
    return Map.of();
  }

  @Override
  public String environment() {
    return "dev";
  }

  @Override
  public Map<String, String> slotImages() {
    return Map.of();
  }

  @Override
  public Optional<String> probeImage() {
    return Optional.empty();
  }

  @Override
  public Optional<String> domain() {
    return Optional.empty();
  }

  @Override
  public String publicUrl() {
    return "wss://system.qits.invalid";
  }

  @Override
  public RegistryMirrors registryMirrors() {
    return new RegistryMirrors() {
      @Override
      public Optional<List<String>> registryHosts() {
        return Optional.empty();
      }

      @Override
      public Optional<List<String>> mirrorHosts() {
        return Optional.empty();
      }

      @Override
      public Optional<List<String>> upstreams() {
        return Optional.empty();
      }
    };
  }

  @Override
  public Docker docker() {
    return new Docker() {
      @Override
      public Duration timeout() {
        return Duration.ofSeconds(30);
      }

      @Override
      public Duration execGrace() {
        return Duration.ofSeconds(10);
      }
    };
  }

  @Override
  public Optional<String> runnerVersionOverride() {
    return Optional.empty();
  }

  @Override
  public Duration reconnectGrace() {
    return Duration.ofMinutes(2);
  }

  @Override
  public String commissionReconcileInterval() {
    return "1h";
  }

  @Override
  public Duration estateInterval() {
    return Duration.ofMinutes(1);
  }

  @Override
  public Healthcheck healthcheck() {
    return new Healthcheck() {
      @Override
      public List<Duration> schedule() {
        return List.of();
      }

      @Override
      public Duration timeout() {
        return Duration.ofMinutes(5);
      }

      @Override
      public String sweepInterval() {
        return "1m";
      }
    };
  }

  @Override
  public Idp idp() {
    return new Idp() {
      @Override
      public String url() {
        return idpUrl;
      }

      @Override
      public Optional<String> clientId() {
        return Optional.of(clientId);
      }

      @Override
      public Optional<String> clientSecret() {
        return Optional.of(secret);
      }

      @Override
      public Duration patience() {
        return Duration.ZERO;
      }

      @Override
      public Duration requestTimeout() {
        return Duration.ofSeconds(5);
      }
    };
  }
}
