# Build stage
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app

# Copy dependencies, sources and web directory
COPY lib/ ./lib/
COPY src/ ./src/
COPY web/ ./web/

# Compile all Java files into the out directory
RUN mkdir -p out && javac -d out -cp "lib/*" src/com/campusconnect/*.java

# Runtime stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

COPY --from=builder /app/out ./out
COPY --from=builder /app/lib ./lib
COPY --from=builder /app/web ./web

# Render and other free hosts inject $PORT automatically
ENV PORT=8080
ENV WEB_DIR=web

EXPOSE 8080

CMD ["sh", "-c", "java -cp \"out:lib/*\" com.campusconnect.Main"]
