# fk-app-gerente nao e um bounded context (ADR-0013): so a experiencia do gerente. A imagem
# tambem carrega o nginx que serve os estaticos e faz reverse proxy de /bff/ para bff-gerente,
# mantendo frontend e BFF na mesma origem -- o que torna o cookie Secure do AC19 exercitado de
# verdade, nao encenado (ver nginx.conf).
FROM node:22-alpine AS build
WORKDIR /workspace

COPY src/fk-app-gerente/package.json src/fk-app-gerente/package-lock.json ./
RUN npm ci

COPY src/fk-app-gerente/ ./
RUN npx ng build

FROM nginx:alpine
COPY --from=build /workspace/dist/fk-app-gerente/browser /usr/share/nginx/html
COPY src/fk-app-gerente/nginx.conf /etc/nginx/nginx.conf

EXPOSE 4200
