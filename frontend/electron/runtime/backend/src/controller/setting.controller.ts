import type { Request, Response } from "express";
import { sendResponse } from "../utils/response.ts";
import { authMessages, statusCode } from "../constants/constants.ts";
import { prisma } from "../config/prisma.ts";

export const updateSettings = async (req: Request, res: Response) => {
  try {
    const {
      UL_PRB_Utilization_Rate,
      DL_PRB_Utilization_Rate,
      E_RAB_Drop_Rate,
      RRC_Drop_Rate,
      Initial_ERAB_Establishment_Success_Rate,
      RRC_Establishment_Success_Rate,
      E_RAB_Setup_Success_Rate,
      VOLTE_CSSR_Eric,
      VOLTE_DCR_Eric,
      Inter_Freq_HOSR,
      Intra_Freq_HOSR,
      Max_RRC_Users,
      CSFB_Success_Rate,
    } = req.body;

    console.log("updateSettings - Received:", req.body);

    const thresholdData = {
      UL_PRB_Utilization_Rate,
      DL_PRB_Utilization_Rate,
      E_RAB_Drop_Rate,
      RRC_Drop_Rate,
      Initial_ERAB_Establishment_Success_Rate,
      RRC_Establishment_Success_Rate,
      E_RAB_Setup_Success_Rate,
      VOLTE_CSSR_Eric,
      VOLTE_DCR_Eric,
      Inter_Freq_HOSR,
      Intra_Freq_HOSR,
      CSFB_Success_Rate,
      Max_RRC_Users,
    };

    const setting = await prisma.threshold.upsert({
      where: { id: 1 },
      update: thresholdData,
      create: {
        id: 1,
        ...thresholdData,
      },
    });

    console.log("Threshold saved to DB:", setting);

    return sendResponse(
      res,
      statusCode.OK,
      "Threshold updated successfully",
      true,
      setting
    );
  } catch (error) {
    console.error("Update threshold Error:", error);

    if (error instanceof Error) {
      console.error("Error details:", error.message);
      console.error("Stack:", error.stack);
    }

    return sendResponse(
      res,
      statusCode.INTERNAL_SERVER_ERROR,
      authMessages.SERVER_ERROR,
      false
    );
  }
};

export const getSettings = async (req: Request, res: Response) => {
  try {
    console.log("getSettings - Fetching from DB...");

    const setting = await prisma.threshold.upsert({
      where: { id: 1 },
      update: {},
      create: { id: 1 },
    });

    console.log(" Retrieved setting:", setting);

    return sendResponse(
      res,
      statusCode.OK,
      "Threshold retrieved successfully",
      true,
      setting
    );
  } catch (error) {
    console.error(" Get threshold Error:", error);
    return sendResponse(
      res,
      statusCode.INTERNAL_SERVER_ERROR,
      authMessages.SERVER_ERROR,
      false
    );
  }
};
