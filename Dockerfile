ARG IMAGE_BASE=clojure
ARG BUILD_IMAGE=${IMAGE_BASE}:temurin-17-lein-bookworm
ARG RUN_IMAGE=${BUILD_IMAGE}-slim

FROM ${BUILD_IMAGE} AS build
WORKDIR /maelstrom

# TODO: Figure out which files to ignore
COPY . .

RUN lein deps
RUN lein uberjar
RUN mv /maelstrom/target/maelstrom*standalone.jar /maelstrom/maelstrom.jar

FROM ${RUN_IMAGE} AS run
WORKDIR /maelstrom

COPY --from=build /maelstrom/maelstrom.jar /maelstrom/maelstrom.jar

# maelstrom requires explicit root user within Docker
USER root
ENTRYPOINT ["java", "-Djava.awt.headless=true", "-jar", "/maelstrom/maelstrom.jar"]
