# syntax=docker/dockerfile:1
FROM node:24.16.0-alpine AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-fund --no-audit
COPY . .
RUN npm run build

FROM nginx:1.28.0-alpine
COPY nginx.conf /etc/nginx/nginx.conf
COPY nginx.template /etc/nginx/templates/default.conf.template
COPY --from=build /workspace/dist/prospero-frontend/browser /usr/share/nginx/html
USER nginx
EXPOSE 8080
CMD ["nginx", "-g", "daemon off;"]
