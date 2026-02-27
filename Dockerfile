FROM node:18-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci --only=production
COPY src/ ./src/

FROM node:18-alpine
WORKDIR /app
COPY --from=builder /app .
EXPOSE 3000
USER node
CMD ["node", "src/index.js"]
