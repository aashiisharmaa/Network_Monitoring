import type { Request, Response } from "express";
import { sendResponse } from "../utils/response.ts";
import { authConstants, authMessages, statusCode } from "../constants/constants.ts";
import { prisma } from "../config/prisma.ts";
import {
  comparePassword,
  generateToken,
  hashPassword,
} from "../utils/auth.jwt.ts";

const createSessionAndRespond = async (
  res: Response,
  user: { id: number; email: string },
  message: string
) => {
  const token = generateToken(user.id);
  const expiresAt = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000);

  await prisma.session.create({
    data: {
      userId: user.id,
      token,
      expiresAt,
    },
  });

  return sendResponse(res, statusCode.OK, message, true, {
    token,
    user: { id: user.id, email: user.email },
  });
};

export const userRegister = async (req: Request, res: Response) => {
  try {
    const { email, password } = req.body;

    if (!email || !password) {
      return sendResponse(
        res,
        statusCode.BAD_REQUEST,
        authMessages.ALL_FIELDS_REQUIRED || "Email and password are required"
      );
    }

    if (!authConstants.PASSWORD_REGEX.test(password)) {
      return sendResponse(
        res,
        statusCode.BAD_REQUEST,
        authMessages.PASSWORD_VALIDATION_FAILED
      );
    }

    const existingUser = await prisma.user.findUnique({ where: { email } });
    if (existingUser) {
      return sendResponse(
        res,
        statusCode.CONFLICT,
        authMessages.USER_EXISTS_WITH_EMAIL
      );
    }

    const hashedPassword = await hashPassword(password);
    const user = await prisma.user.create({
      data: {
        email,
        password: hashedPassword,
      },
      select: {
        id: true,
        email: true,
      },
    });

    return createSessionAndRespond(
      res,
      user,
      authMessages.SIGNUP_SUCCESSFULLY || "User created successfully"
    );
  } catch (error: any) {
    console.error("Register Error:", error);
    return sendResponse(
      res,
      statusCode.INTERNAL_SERVER_ERROR,
      authMessages.SERVER_ERROR || "Internal server error"
    );
  }
};

export const userLogin = async (req: Request, res: Response) => {
  try {
    const { email, password } = req.body;

    if (!email || !password) {
      return sendResponse(
        res,
        statusCode.BAD_REQUEST,
        authMessages.ALL_FIELDS_REQUIRED || "Email and password are required"
      );
    }

    const user = await prisma.user.findUnique({ where: { email } });
    if (!user)
      return sendResponse(
        res,
        statusCode.UNAUTHORIZED,
        authMessages.INVALID_CREDENTIALS || "Invalid email or password"
      );

    const isPasswordValid = await comparePassword(password, user.password);
    const isLegacyPasswordMatch = user.password === password;

    if (!isPasswordValid && !isLegacyPasswordMatch) {
      return sendResponse(
        res,
        statusCode.UNAUTHORIZED,
        authMessages.INVALID_CREDENTIALS || "Invalid email or password"
      );
    }

    // Seamless migration for existing plaintext records.
    if (isLegacyPasswordMatch) {
      const hashedPassword = await hashPassword(password);
      await prisma.user.update({
        where: { id: user.id },
        data: { password: hashedPassword },
      });
    }

    return createSessionAndRespond(
      res,
      user,
      authMessages.LOGIN_SUCCESSFULLY || "Login successful"
    );
  } catch (error: any) {
    console.error("Login Error:", error);
    return sendResponse(
      res,
      statusCode.INTERNAL_SERVER_ERROR,
      authMessages.SERVER_ERROR || "Internal server error"
    );
  }
};

export const logout = async (req: Request, res: Response) => {
  const authHeader = req.headers.authorization;
  if (!authHeader)
    return res.status(400).json({ message: "No token provided" });

  const token = authHeader.split(" ")[1];
  await prisma.session.deleteMany({ where: { token } });

  res.json({ message: "Logged out successfully" });
};
