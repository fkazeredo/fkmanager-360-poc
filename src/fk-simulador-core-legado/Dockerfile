# Build de contexto = raiz do repositorio (docker-compose.yaml usa build.context: .), porque o
# reactor Maven precisa do pom.xml agregador para resolver o parent deste modulo (ADR-0011).
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace

COPY pom.xml mvnw ./
COPY .mvn/ .mvn/
COPY src/fk-simulador-core-legado/pom.xml src/fk-simulador-core-legado/pom.xml
RUN chmod +x mvnw

COPY src/fk-simulador-core-legado/src src/fk-simulador-core-legado/src
RUN ./mvnw -f src/fk-simulador-core-legado/pom.xml -o -B package -DskipTests \
    || ./mvnw -f src/fk-simulador-core-legado/pom.xml -B package -DskipTests

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/src/fk-simulador-core-legado/target/fk-simulador-core-legado.jar app.jar
USER app
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
