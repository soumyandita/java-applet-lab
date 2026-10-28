FROM eclipse-temurin:8-jdk-jammy

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends xvfb xauth \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY src/AppletServer.java /app/src/AppletServer.java
RUN javac -encoding UTF-8 -d /app/classes /app/src/AppletServer.java \
    && useradd --create-home --uid 10001 appuser \
    && chown -R appuser:appuser /app

USER appuser
ENV PORT=8080
EXPOSE 8080
CMD ["bash", "-lc", "xvfb-run -a java -Djava.awt.headless=false -cp /app/classes AppletServer"]
