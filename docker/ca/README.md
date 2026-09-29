# Extra CA certificates for the image build

Empty on purpose. On an ordinary machine nothing needs to go here.

Drop a PEM-encoded certificate authority in this directory (`*.crt` or `*.pem`) when the machine
building the images sends outbound HTTPS through a **TLS-terminating proxy** — a corporate MITM
appliance, or a cloud dev container. Without it the JVM inside the build container refuses to talk to
Maven Central:

```
PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException:
unable to find valid certification path to requested target
```

The `Dockerfile`'s builder stage imports whatever it finds here into the JDK truststore before Maven
runs, splitting multi-certificate bundles so each one is imported separately.

Certificate files themselves are gitignored: they are specific to one machine's proxy and have no
business in the repository.

In a Claude Code cloud container the bundle is at `/root/.ccr/ca-bundle.crt`:

```bash
cp /root/.ccr/ca-bundle.crt docker/ca/
```
