import "dotenv/config";
import cors from "cors";
import express from "express";
import { testConnection } from "./config/connectdb.ts";
import { mainRouter } from "./routes/main.routes.ts";

const app = express();
const port = Number(process.env.PORT) || 3000;

const allowedOrigins = (process.env.CORS_ORIGINS ||
  "http://localhost:5173,http://127.0.0.1:5173,http://localhost:4173,http://127.0.0.1:4173,file://")
  .split(",")
  .map((origin) => origin.trim())
  .filter(Boolean);

app.use(express.json());
app.use(
  cors({
    origin: (origin, callback) => {
      if (!origin || origin === "null") {
        callback(null, true);
        return;
      }

      if (allowedOrigins.includes(origin)) {
        callback(null, true);
        return;
      }

      callback(new Error(`CORS blocked for origin: ${origin}`));
    },
    methods: ["GET", "POST", "PUT", "DELETE", "OPTIONS"],
    allowedHeaders: ["Content-Type", "Authorization"],
    credentials: true,
  })
);

testConnection();

app.use("/", mainRouter);

app.listen(port, "0.0.0.0", () => {
  console.log(`Server running on port ${port}`);
});
