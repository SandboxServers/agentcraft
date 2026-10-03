# syntax=docker/dockerfile:1.7
#
# AgentCraft dedicated server: Fabric 26.3 + Fabric API + the AgentCraft mod, on Java 25.
#
# Built by .github/workflows/release-container.yml and published to
# ghcr.io/<owner>/agentcraft-server. The colo pulls the rolling `latest-prerelease` tag through
# Watchtower (deploy/compose.yaml). Build locally from the repo root:
#
#   docker build -f deploy/Dockerfile -t agentcraft-server:dev .
#
# The world, configs, logs and backups live in /data (a named volume); everything else in the
# image is replaced on every update. Nothing in here is specific to any host: no addresses,
# hostnames or credentials (this repository is public).

ARG MC_VERSION=26.3
ARG FABRIC_LOADER=0.19.5
ARG FABRIC_INSTALLER=1.1.2
ARG FABRIC_API=0.161.0+26.3
ARG RCON_CLI=1.7.7

# ── 1. Build the mod jar ────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS mod
WORKDIR /src/mod
COPY mod/ ./
# Loom downloads Minecraft and Fabric here. The cache mount keeps them between local builds;
# CI uses the buildx GHA cache for the layers instead.
RUN --mount=type=cache,target=/root/.gradle \
    sh ./gradlew --no-daemon --console=plain build \
 && jar="$(find build/libs -maxdepth 1 -name 'agentcraft-*.jar' ! -name '*-sources.jar' | head -n 1)" \
 && test -n "$jar" && cp "$jar" /agentcraft.jar

# ── 2. Fetch the server launcher, Fabric API and rcon-cli, and pre-download Minecraft ───────
FROM eclipse-temurin:25-jdk AS fetch
ARG MC_VERSION
ARG FABRIC_LOADER
ARG FABRIC_INSTALLER
ARG FABRIC_API
ARG RCON_CLI
ARG TARGETARCH
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/agentcraft
RUN curl -fsSL -o fabric-server-launch.jar \
      "https://meta.fabricmc.net/v2/versions/loader/${MC_VERSION}/${FABRIC_LOADER}/${FABRIC_INSTALLER}/server/jar" \
 && mkdir -p mods \
 && curl -fsSL -o "mods/fabric-api-${FABRIC_API}.jar" \
      "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/${FABRIC_API}/fabric-api-${FABRIC_API}.jar" \
 && arch="${TARGETARCH:-amd64}" \
 && curl -fsSL "https://github.com/itzg/rcon-cli/releases/download/${RCON_CLI}/rcon-cli_${RCON_CLI}_linux_${arch}.tar.gz" \
      | tar -xz -C /usr/local/bin rcon-cli
# Run the launcher once without an accepted EULA: it downloads the vanilla server
# (versions/<mc>/) and the libraries, then Minecraft exits because the EULA is not accepted.
# The image then starts without any download at runtime. `.fabric/` holds the launcher's cache
# (without it the launcher re-downloads every library); the entrypoint copies it into the
# writable volume whenever the image changes.
RUN java -jar fabric-server-launch.jar nogui || true; \
    test -d "versions/${MC_VERSION}" && test -d libraries && test -d .fabric \
 && rm -rf world logs eula.txt server.properties *.json config crash-reports

# ── 3. Runtime ──────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre
ARG MC_VERSION
ARG FABRIC_LOADER
ARG FABRIC_API
ARG AGENTCRAFT_GIT_SHA=unknown
RUN apt-get update && apt-get install -y --no-install-recommends tini \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 10001 minecraft \
 && useradd --system --uid 10001 --gid minecraft --home-dir /data --no-create-home minecraft \
 && mkdir -p /data && chown minecraft:minecraft /data
COPY --from=fetch /opt/agentcraft/ /opt/agentcraft/
COPY --from=fetch /usr/local/bin/rcon-cli /usr/local/bin/rcon-cli
COPY --from=mod /agentcraft.jar /opt/agentcraft/mods/agentcraft.jar
COPY deploy/server/ /opt/agentcraft/defaults/
COPY deploy/bin/ /opt/agentcraft/bin/
# Strip CR in case the build context came from a Windows checkout with autocrlf.
RUN sed -i 's/\r$//' /opt/agentcraft/bin/*.sh /opt/agentcraft/defaults/server.properties \
 && chmod 0755 /opt/agentcraft/bin/*.sh \
 && echo "${AGENTCRAFT_GIT_SHA}" > /opt/agentcraft/VERSION

ENV AGENTCRAFT_HOME=/opt/agentcraft \
    MEMORY=3G \
    JAVA_OPTS="" \
    AGENTCRAFT_GIT_SHA=${AGENTCRAFT_GIT_SHA} \
    MC_VERSION=${MC_VERSION}

LABEL org.opencontainers.image.title="agentcraft-server" \
      org.opencontainers.image.description="AgentCraft dedicated server (Fabric ${MC_VERSION}, loader ${FABRIC_LOADER}, Fabric API ${FABRIC_API})" \
      org.opencontainers.image.licenses="MIT"

USER minecraft
WORKDIR /data
VOLUME ["/data"]
EXPOSE 25565/tcp

# Probed from inside the container: a host-side probe of a published port always connects.
HEALTHCHECK --interval=30s --timeout=5s --start-period=180s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/25565' || exit 1

# tini forwards SIGTERM to Java, whose shutdown hook saves the world (docker stop, Watchtower).
ENTRYPOINT ["/usr/bin/tini", "--", "/opt/agentcraft/bin/entrypoint.sh"]
