# ============================================================
# FINAGENT frontend — multi-stage image (Phase 12, hardened Phase 17)
# Stage 1: reproducible npm build → Stage 2: unprivileged nginx static hosting.
# VITE_API_BASE_URL defaults to "" (same-origin /api via nginx proxy).
# Override with --build-arg for direct-backend setups (then configure
# FINAGENT_CORS_ALLOWED_ORIGINS on the backend accordingly).
# No dev server, no node_modules, no secrets in the final image.
# Phase 17: the runtime stage is nginxinc/nginx-unprivileged (non-root `nginx`
# user, listens on 8080 — mapped to host 80 in docker-compose.yml).
# ============================================================

FROM node:20-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
ARG VITE_API_BASE_URL=""
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

# --- Static hosting (non-root) ---
FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
