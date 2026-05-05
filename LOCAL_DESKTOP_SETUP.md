# Local Setup (Frontend + Backend)

This project now runs as local frontend + backend only.

## Prerequisites

- Node.js 20+ and npm
- Java 17+
- MySQL running locally

## 1. Configure Environment Files

Frontend:

```bash
cp frontend/.env.example frontend/.env
```

The desktop launcher now sets the Java backend env vars automatically.
If you run `backend-java/run-backend.ps1` directly, make sure `DATABASE_URL`
matches your local MySQL setup.

## 2. Install Dependencies

```bash
npm install --prefix frontend
```

## 3. Start Backend

```bash
powershell -ExecutionPolicy Bypass -File backend-java/run-backend.ps1
```

Backend API runs on:
- `http://localhost:3000`

## 4. Start Frontend

In another terminal:

```bash
npm run dev --prefix frontend
```

Frontend runs on:
- `http://127.0.0.1:5173`

## Optional Windows Helper Scripts

Start both (backend + frontend):

```bat
scripts\start-all.cmd
```

Stop dev processes:

```bat
scripts\stop-all.cmd
```

Skip install in start script:

```bat
scripts\start-all.cmd -SkipInstall
```
