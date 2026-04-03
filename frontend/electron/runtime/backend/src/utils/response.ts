import type { Response } from "express";

export const sendResponse = (
  res: Response,
  statusCode: number,
  message: string,
  success = false,
  data?: any
): Response => {
  return res.status(statusCode).json({
    message,
    success,
    data: data,
  });
};
