# Local Setup (Frontend + Backend)

This project now runs as local frontend + backend only.

## Prerequisites

- Node.js 20+ and npm
- MySQL running locally

## 1. Configure Environment Files

Backend:

```bash
cp backend/.env.example backend/.env
```

Frontend:

```bash
cp frontend/.env.example frontend/.env
```

Update backend `DATABASE_URL` in `backend/.env` for your local MySQL.

Example:

```env
DATABASE_URL=mysql://admin:Admin%401234@localhost:3306/kpi_tool
```

## 2. Install Dependencies

```bash
npm install --prefix backend
npm install --prefix frontend
```

## 3. Start Backend

```bash
npm run dev --prefix backend
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
