# Contexto de build = raiz do repositorio (o reactor precisa do pom.xml agregador).
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace

COPY pom.xml mvnw ./
COPY .mvn/ .mvn/
COPY src/carteira-clientes/servico-carteira-clientes/pom.xml src/carteira-clientes/servico-carteira-clientes/pom.xml
RUN chmod +x mvnw

COPY src/carteira-clientes/servico-carteira-clientes/src src/carteira-clientes/servico-carteira-clientes/src
RUN ./mvnw -f src/carteira-clientes/servico-carteira-clientes/pom.xml -o -B package -DskipTests \
    || ./mvnw -f src/carteira-clientes/servico-carteira-clientes/pom.xml -B package -DskipTests

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/src/carteira-clientes/servico-carteira-clientes/target/servico-carteira-clientes.jar app.jar
USER app
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
