import jwt from "jsonwebtoken";
import bcrypt from "bcrypt";

const JWT_SECRET = process.env.JWT_SECRET || "change-me-in-env";
const TOKEN_EXPIRY = "7d" as const;

if (!process.env.JWT_SECRET) {
  console.warn(
    "JWT_SECRET is not set. Using insecure fallback secret; set JWT_SECRET in environment."
  );
}

export const generateToken = (userId: number) => {
  return jwt.sign({ userId }, JWT_SECRET, { expiresIn: TOKEN_EXPIRY });
};

export const verifyToken = (token: string) => {
  try {
    const decoded = jwt.verify(token, JWT_SECRET);
    return decoded as { userId: number; iat: number; exp: number };
  } catch {
    return null;
  }
};

export const hashPassword = async (password: string) => {
  return bcrypt.hash(password, 10);
};

export const comparePassword = async (
  password: string,
  storedPassword: string
) => {
  return bcrypt.compare(password, storedPassword);
};
