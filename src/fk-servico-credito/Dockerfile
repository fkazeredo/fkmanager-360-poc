# Contexto de build = raiz do repositorio (o reactor precisa do pom.xml agregador).
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace

COPY pom.xml mvnw ./
COPY .mvn/ .mvn/
COPY src/fk-servico-credito/pom.xml src/fk-servico-credito/pom.xml
RUN chmod +x mvnw

COPY src/fk-servico-credito/src src/fk-servico-credito/src
RUN ./mvnw -f src/fk-servico-credito/pom.xml -o -B package -DskipTests \
    || ./mvnw -f src/fk-servico-credito/pom.xml -B package -DskipTests

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/src/fk-servico-credito/target/fk-servico-credito.jar app.jar
USER app
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
