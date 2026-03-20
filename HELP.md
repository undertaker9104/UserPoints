# User Points Service - Setup Guide

## Prerequisites

- Java 21+
- Docker & Docker Compose
- Maven

## Quick Start

### 1. Start Infrastructure Services

```bash
docker-compose up -d
```

This starts:
- MySQL (port 3306)
- Redis (port 6379)
- RocketMQ Namesrv (port 9876)
- RocketMQ Broker (port 10911)
- RocketMQ Console (port 8088)

### 2. Verify Services

```bash
# Check MySQL
docker exec mysql mysql -utaskuser -ptaskpass -e "USE taskdb; SHOW TABLES;"

# Check Redis
docker exec redis redis-cli ping

# Check RocketMQ Console
open http://localhost:8088
```

### 3. Run Application

```bash
# Windows
set JAVA_HOME=C:\path\to\jdk21
./mvnw.cmd spring-boot:run

# Linux/Mac
export JAVA_HOME=/path/to/jdk21
./mvnw spring-boot:run
```

Application runs on: http://localhost:8080

## API Endpoints

### Add Points
```bash
curl -X POST http://localhost:8080/points \
  -H "Content-Type: application/json" \
  -d '{"userId": "user_123", "amount": 100, "reason": "signup_bonus"}'
```

### Get User Points
```bash
curl http://localhost:8080/points/user_123
```

### Get Leaderboard
```bash
curl http://localhost:8080/points/leaderboard
```

### Update Reason
```bash
curl -X PUT http://localhost:8080/points/1 \
  -H "Content-Type: application/json" \
  -d '{"reason": "corrected_bonus"}'
```

### Delete User Points
```bash
curl -X DELETE http://localhost:8080/points/user_123
```

## Running Tests

```bash
./mvnw test
```

## Architecture Highlights

- **Distributed Lock**: Redis-based lock prevents concurrent modifications
- **Optimistic Lock**: Database version column prevents lost updates
- **Cache-Aside**: Read from Redis first, fallback to MySQL
- **Async Updates**: RocketMQ for eventual consistency
- **Idempotent Processing**: Event ID tracking prevents duplicate processing
