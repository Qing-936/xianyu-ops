FROM node:22-alpine AS build
WORKDIR /app
RUN npm install -g pnpm@11.15.1
COPY web/package.json web/pnpm-lock.yaml web/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY web .
RUN pnpm build

FROM nginx:1.27-alpine
COPY docker/nginx-admin.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
