# The server runs the backend jar that passed the tests on the Mac
# (infra/release/backend.jar, put there by ops/release-backend.sh), so it needs
# neither Maven nor a JDK. Original price lists are kept in /data/import-archive.
# Ubuntu, not Alpine: ONNX Runtime (name meaning, V135) is built for glibc.
FROM eclipse-temurin:21-jre
# ops/start.sh removes old, unnamed images with this label after a release.
LABEL rs.pametnakupovina.image=backend

# The same ids as the Alpine image had, so the archive volume stays writable.
RUN groupadd --system --gid 101 app && useradd --system --uid 100 --gid app --no-create-home app \
    && mkdir -p /data/import-archive \
    && chown app:app /data/import-archive

WORKDIR /app
COPY release/backend.jar app.jar

USER app
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
