# One Dockerfile for all five services.
#
# The builder stage compiles the whole Maven reactor once; the runtime stage picks out a single
# module's runnable jar, chosen by the MODULE build argument. Docker caches the builder stage across
# the five images, so `docker compose up --build` compiles the project once, not five times.
#
# The jar is the "-app" classified one. See docs/decision-log.md DL-004: the plain jar stays each
# module's main artifact so Failsafe does not end up with a fat jar on its test classpath.

# ---------------------------------------------------------------------------- build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Optional: trust an extra certificate authority while building.
#
# On an ordinary machine docker/ca/ is empty and this step does nothing. It exists for environments
# where outbound HTTPS goes through a TLS-terminating proxy - a corporate MITM appliance, or a cloud
# dev container - because the JVM will otherwise refuse to talk to Maven Central with
# "PKIX path building failed: unable to find valid certification path to requested target".
#
# To use it, drop the proxy's CA in PEM form into docker/ca/ (the directory is gitignored) and
# rebuild. A PEM bundle may hold several certificates, and keytool imports only the first from a
# file, so each one is split out and imported on its own.
COPY docker/ca/ /tmp/extra-ca/
RUN set -eu; \
    imported=0; \
    for bundle in /tmp/extra-ca/*.crt /tmp/extra-ca/*.pem; do \
        [ -f "$bundle" ] || continue; \
        csplit -sz -f /tmp/cert- -b '%02d.pem' "$bundle" '/-----BEGIN CERTIFICATE-----/' '{*}'; \
        for cert in /tmp/cert-*.pem; do \
            [ -f "$cert" ] || continue; \
            keytool -importcert -noprompt -trustcacerts \
                -alias "extra-$(basename "$bundle")-$(basename "$cert" .pem)" \
                -file "$cert" \
                -keystore "$JAVA_HOME/lib/security/cacerts" -storepass changeit >/dev/null; \
            imported=$((imported + 1)); \
        done; \
        rm -f /tmp/cert-*.pem; \
    done; \
    echo "trusted $imported extra CA certificate(s)"

# Copy the POMs on their own first and resolve dependencies, so a source-only change does not
# re-download the world. The reactor has to be complete for this to resolve at all.
COPY pom.xml ./
COPY subsystem-clients/pom.xml   subsystem-clients/
COPY catalog-service/pom.xml     catalog-service/
COPY billing-service/pom.xml     billing-service/
COPY activation-service/pom.xml  activation-service/
COPY ops-console/pom.xml         ops-console/
COPY stripe-sim/pom.xml          stripe-sim/
# activation-service generates JAXB stubs from a checked-in copy of billing's XSD, and
# subsystem-clients generates its own from billing's original by relative path, so both schemas have
# to be present before dependency resolution touches those modules.
COPY billing-service/src/main/resources/xsd/      billing-service/src/main/resources/xsd/
COPY activation-service/src/main/resources/xsd/   activation-service/src/main/resources/xsd/
RUN mvn -B -q de.qaware.maven:go-offline-maven-plugin:resolve-dependencies 2>/dev/null \
 || mvn -B -q dependency:go-offline -DskipTests \
 || echo "dependency pre-fetch was incomplete; the package step below will fetch the rest"

COPY . .
# Tests are not run here. They need Docker (Testcontainers), and building an image inside a build
# that then wants to start containers is a knot not worth tying. `mvn verify` on the host is the
# test entry point; see docs/run-guide.md.
RUN mvn -B -DskipTests package

# -------------------------------------------------------------------------- runtime
FROM eclipse-temurin:21-jre-jammy AS runtime

ARG MODULE
ARG VERSION=1.0.0-SNAPSHOT

# Nothing is installed here on purpose. No curl, no wget: the image stays a JRE and whatever this
# project built. Compose's health checks talk HTTP through bash's /dev/tcp instead - see the
# healthcheck commands in docker-compose.yml. (Installing curl would also mean apt-get over HTTP,
# which fails outright behind a TLS-terminating proxy: "the repository is not signed".)

# Runs as a normal user rather than root.
RUN useradd --system --create-home --uid 10001 legacy
WORKDIR /app

COPY --from=build /build/${MODULE}/target/${MODULE}-${VERSION}-app.jar /app/app.jar

# The clearing-house file exchange, created here and owned by the runtime user.
#
# This has to happen in the image, not at start-up. Docker seeds an empty named volume from whatever
# the image has at the mount point, ownership included - so if these directories do not exist in the
# image, the volume is created root-owned and billing (running as uid 10001) cannot write its
# settlement files into it. The symptom is a 502 from exportPaymentBatch saying
# "could not write settlement file".
RUN mkdir -p /var/lib/legacy/batch-exchange/outbox \
             /var/lib/legacy/batch-exchange/inbox \
             /var/lib/legacy/batch-exchange/archive \
 && chown -R legacy:legacy /app /var/lib/legacy

USER legacy

# Container memory is small and the JVM's default heap sizing is generous; cap it explicitly so five
# JVMs plus PostgreSQL fit comfortably.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
