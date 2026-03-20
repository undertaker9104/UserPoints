# User Points Service - Setup Guide

## Prerequisites

- Docker & Docker Compose (required)
- Java 21 (optional, only for local development)
- Maven (optional, only for local development)

---

## Quick Start (One Command)

```bash
# Start everything (MySQL, Redis, RocketMQ, Application)
docker-compose up -d --build

# Wait for all services to be ready (about 60-90 seconds)
# Check status
docker-compose ps

# Test the API
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

---

## Verify Services

```bash
# Check all containers are running
docker-compose ps

# Check application logs
docker logs user-points-app

# Check application health
curl http://localhost:8080/actuator/health

# Check MySQL
docker exec mysql mysql -utaskuser -ptaskpass -e "USE taskdb; SHOW TABLES;"

# Check Redis
docker exec redis redis-cli ping

# Check RocketMQ Console
open http://localhost:8088
```

---

## Stop Services

```bash
# Stop all services
docker-compose down

# Stop and remove data volumes (clean reset)
docker-compose down -v
```

---

## Local Development (Without Docker for App)

If you want to run the application locally for development:

```bash
# 1. Start only infrastructure
docker-compose up -d mysql redis rocketmq-namesrv rocketmq-broker

# 2. Wait for services to be ready
sleep 30

# 3. Run application locally
./mvnw spring-boot:run

# Or on Windows
./mvnw.cmd spring-boot:run
```

---

## Running Tests

```bash
./mvnw test
```

---

## Architecture Highlights

- **Distributed Lock**: Redis-based lock prevents concurrent modifications
- **Optimistic Lock**: Database version column prevents lost updates
- **Cache-Aside**: Read from Redis first, fallback to MySQL
- **Async Updates**: RocketMQ for eventual consistency
- **Idempotent Processing**: Event ID tracking prevents duplicate processing
