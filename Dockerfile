FROM eclipse-temurin:21-jre-jammy

RUN groupadd --gid 10001 kafka-gitops && \
    useradd --uid 10001 --gid 10001 --create-home --home-dir /home/kafka-gitops --shell /usr/sbin/nologin kafka-gitops && \
    mkdir -p /workspace && \
    chown kafka-gitops:kafka-gitops /workspace

COPY --chown=kafka-gitops:kafka-gitops --chmod=0755 ./build/output/kafka-gitops /usr/local/bin/kafka-gitops

WORKDIR /workspace
USER kafka-gitops:kafka-gitops

ENTRYPOINT ["/usr/local/bin/kafka-gitops"]
