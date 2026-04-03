import { prisma } from "./prisma.ts";

export async function testConnection() {
  try {
    await prisma.$queryRaw`SELECT 1`;
    console.log(" Database connected successfully");
  } catch (error) {
    console.error(" Database connection failed:", error);
  }
}
