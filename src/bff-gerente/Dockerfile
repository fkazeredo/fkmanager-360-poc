# Contexto de build = raiz do repositorio (o reactor precisa do pom.xml agregador).
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace

COPY pom.xml mvnw ./
COPY .mvn/ .mvn/
COPY src/bff-gerente/pom.xml src/bff-gerente/pom.xml
RUN chmod +x mvnw

COPY src/bff-gerente/src src/bff-gerente/src
RUN ./mvnw -f src/bff-gerente/pom.xml -o -B package -DskipTests \
    || ./mvnw -f src/bff-gerente/pom.xml -B package -DskipTests

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/src/bff-gerente/target/bff-gerente.jar app.jar
USER app
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
