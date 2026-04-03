import { Request, Response, NextFunction } from "express";
import { verifyToken } from "../utils/auth.jwt.ts";
import { prisma } from "../config/prisma.ts";

export const authMiddleware = async (
  req: Request,
  res: Response,
  next: NextFunction
) => {
  const authHeader = req.headers.authorization;

  if (!authHeader?.startsWith("Bearer ")) {
    return res.status(401).json({ message: "Missing or invalid token" });
  }

  const token = authHeader.split(" ")[1];
  const decoded = verifyToken(token);
  if (!decoded) return res.status(401).json({ message: "Invalid token" });

  const session = await prisma.session.findUnique({ where: { token } });
  if (!session || new Date() > session.expiresAt)
    return res.status(401).json({ message: "Session expired or invalid" });

  req.user = { id: decoded.userId };
  next();
};
