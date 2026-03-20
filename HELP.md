# User Points Service - Setup Guide

## Prerequisites

- Docker & Docker Compose (required)
- Java 21 (optional, only for local development)
- Maven (optional, only for local development)

---

## Quick Start (One Command)

```bash
# Start everything (MySQL, Redis, RocketMQ, Application)
docker compose up -d --build

# Wait for all services to be ready (about 60-90 seconds)
# Check status
docker compose ps

# Test the API
curl http://localhost:8080/actuator/health
curl http://localhost:8080/points/leaderboard
```

**That's it!** The application will be available at `http://localhost:8080`

---

## Service Ports

| Service | Port | Description |
|---------|------|-------------|
| Application | 8080 | REST API |
| MySQL | 3306 | Database |
| Redis | 6379 | Cache |
| RocketMQ Namesrv | 9876 | MQ Registry |
| RocketMQ Broker | 10911 | MQ Broker |
| RocketMQ Console | 8088 | MQ Web UI |

---

## API Endpoints

### Add Points
```bash
curl -X POST http://localhost:8080/points \
  -H "Content-Type: application/json" \
  -d '{"userId": "user_123", "amount": 100, "reason": "signup_bonus"}'
```

**Response:**
```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "userId": "user_123",
    "totalPoints": 100
  }
}
```

### Get User Points
```bash
curl http://localhost:8080/points/user_123
```

### Get Leaderboard (Top 10)
```bash
curl http://localhost:8080/points/leaderboard
```

### Update Record Reason
```bash
# Note: {id} is the database record ID (auto-increment), not transactionId
curl -X PUT http://localhost:8080/points/1 \
  -H "Content-Type: application/json" \
  -d '{"reason": "corrected_bonus"}'
```

### Delete User Points
```bash
curl -X DELETE http://localhost:8080/points/user_123
```

---

## Verify Services

```bash
# Check all containers are running
docker compose ps

# Check application logs
docker logs user-points-app

# Check application health
curl http://localhost:8080/actuator/health

# Check MySQL
docker exec mysql mysql -utaskuser -ptaskpass taskdb -e "SHOW TABLES;"

# Check Redis
docker exec redis redis-cli ping

# Check RocketMQ Console (open in browser)
# Windows: start http://localhost:8088
# macOS:   open http://localhost:8088
# Linux:   xdg-open http://localhost:8088
```

---

## Stop Services

```bash
# Stop all services
docker compose down

# Stop and remove data volumes (clean reset)
docker compose down -v
```

---

## Local Development (Without Docker for App)

If you want to run the application locally for development:

```bash
# 1. Start only infrastructure
docker compose up -d mysql redis rocketmq-namesrv rocketmq-broker

# 2. Wait for services to be ready
sleep 30

# 3. Run application locally
# Linux/macOS:
./mvnw spring-boot:run

# Windows PowerShell:
.\mvnw.cmd spring-boot:run

# Windows Git Bash:
./mvnw spring-boot:run
```

---

## Running Tests

```bash
# Linux/macOS:
./mvnw test

# Windows PowerShell:
.\mvnw.cmd test
```

---

## Architecture Highlights

- **RocketMQ Transactional Message**: Guarantees DB-MQ consistency (no data loss)
- **Distributed Lock**: Redis SETNX prevents concurrent modifications to same user
- **Optimistic Lock**: MySQL version column prevents lost updates
- **Cache-Aside Pattern**: Read from Redis first, fallback to MySQL
- **Idempotent Processing**: Event ID tracking prevents duplicate message processing

### Transaction Flow

```
1. API receives add points request
2. Send Half Message to RocketMQ
3. Execute local transaction (acquire lock → update DB)
4. COMMIT message if DB success, ROLLBACK if fails
5. Consumer receives message and updates Redis cache
6. API returns result to client
```

---

## Troubleshooting

### Application won't start
```bash
# Check if all dependencies are running
docker compose ps

# Check application logs
docker logs user-points-app --tail 100
```

### RocketMQ connection issues
```bash
# Ensure broker is fully started (wait 30+ seconds after docker compose up)
docker logs rocketmq-broker --tail 50
```

### MySQL connection refused
```bash
# Check MySQL is healthy
docker exec mysql mysqladmin -utaskuser -ptaskpass ping
```
