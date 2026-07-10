# Build stage
FROM node:22-alpine AS builder
WORKDIR /app

# Copy package metadata files
COPY package.json package-lock.json ./

# Install dependencies cleanly
RUN npm ci

# Copy build config files
COPY tsconfig.json tsconfig.node.json vite.config.ts tailwind.config.js postcss.config.js index.html ./

# Copy source code
COPY src/ ./src/

# Build React application
RUN npm run build

# Web server stage
FROM nginx:1.27-alpine

# Copy static assets from builder stage
COPY --from=builder /app/dist /usr/share/nginx/html

# Copy custom nginx configuration
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Expose web server port
EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
