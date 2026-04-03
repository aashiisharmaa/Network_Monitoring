import type { Request, Response } from "express";
import { prisma } from "../config/prisma.ts";

// ✅ Combined Dashboard Stats (Single API - RECOMMENDED)
export const getDashboardStats = async (req: Request, res: Response) => {
  try {
    const parsedFileId = Number(req.query.fileId);
    const hasFileIdFilter = Number.isFinite(parsedFileId) && parsedFileId > 0;
    const dataWhere = hasFileIdFilter ? { fileId: parsedFileId } : undefined;

    const startTime = Date.now();

    // Run all queries in parallel for maximum performance
    const [
      totalCells,
      totalSites,
      totalBands,
      totalTech,
      totalSectors,
      totalGroups,
      totalRecords,
      latestUpload,
    ] = await Promise.all([
      // Total unique cells (Cell_Name)
      prisma.uploadData.findMany({
        distinct: ["Cell_Name"],
        select: { Cell_Name: true },
        where: { ...(dataWhere || {}), Cell_Name: { not: null } },
      }),

      // Total unique sites (Site)
      prisma.uploadData.findMany({
        distinct: ["Site"],
        select: { Site: true },
        where: { ...(dataWhere || {}), Site: { not: null } },
      }),

      // Total unique bands (Band)
      prisma.uploadData.findMany({
        distinct: ["Band"],
        select: { Band: true },
        where: { ...(dataWhere || {}), Band: { not: null } },
      }),

      // Total unique tech (Tech)
      prisma.uploadData.findMany({
        distinct: ["Tech"],
        select: { Tech: true },
        where: { ...(dataWhere || {}), Tech: { not: null } },
      }),

      // Total unique sectors (Sector_ID)
      prisma.uploadData.findMany({
        distinct: ["Sector_ID"],
        select: { Sector_ID: true },
        where: { ...(dataWhere || {}), Sector_ID: { not: null } },
      }),

      // Total unique groups (Groups)
      prisma.uploadData.findMany({
        distinct: ["Groups"],
        select: { Groups: true },
        where: { ...(dataWhere || {}), Groups: { not: null } },
      }),

      // Total records
      prisma.uploadData.count({
        where: dataWhere,
      }),

      // Latest upload info
      hasFileIdFilter
        ? prisma.uploadHistory.findUnique({
            where: { id: parsedFileId },
            select: {
              fileName: true,
              uploadedBy: true,
              createdAt: true,
              _count: {
                select: { uploadData: true },
              },
            },
          })
        : prisma.uploadHistory.findFirst({
            orderBy: { createdAt: "desc" },
            select: {
              fileName: true,
              uploadedBy: true,
              createdAt: true,
              _count: {
                select: { uploadData: true },
              },
            },
          }),
    ]);

    const executionTime = Date.now() - startTime;

    console.log(`✅ Dashboard stats fetched in ${executionTime}ms`);
    console.log(`  - Cells: ${totalCells.length}`);
    console.log(`  - Sites: ${totalSites.length}`);
    console.log(`  - Bands: ${totalBands.length}`);
    console.log(`  - Tech: ${totalTech.length}`);
    console.log(`  - Sectors: ${totalSectors.length}`);
    console.log(`  - Groups: ${totalGroups.length}`);
    console.log(`  - Total Records: ${totalRecords}`);

    return res.status(200).json({
      success: true,
      data: {
        fileId: hasFileIdFilter ? parsedFileId : null,
        totalCells: totalCells.length,
        totalSites: totalSites.length,
        totalBands: totalBands.length,
        totalTech: totalTech.length,
        totalSectors: totalSectors.length,
        totalGroups: totalGroups.length,
        totalRecords,
        latestUpload: latestUpload || null,
      },
      meta: {
        executionTime: `${executionTime}ms`,
        timestamp: new Date().toISOString(),
      },
    });
  } catch (error: any) {
    console.error("❌ Error fetching dashboard stats:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

// ✅ Get Total Cells
export const getTotalCells = async (req: Request, res: Response) => {
  try {
    console.log("📱 Fetching total cells...");

    const cells = await prisma.uploadData.findMany({
      distinct: ["Cell_Name"],
      select: { Cell_Name: true },
      where: { Cell_Name: { not: null } },
      orderBy: { Cell_Name: "asc" },
    });

    console.log(`✅ Found ${cells.length} unique cells`);

    return res.status(200).json({
      success: true,
      data: {
        total: cells.length,
        cells: cells.map((c: { Cell_Name: string | null }) => c.Cell_Name),
      },
    });
  } catch (error: any) {
    console.error("❌ Error fetching total cells:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

// ✅ Get Total Sites
export const getTotalSites = async (req: Request, res: Response) => {
  try {
    console.log("🏢 Fetching total sites...");

    const sites = await prisma.uploadData.findMany({
      distinct: ["Site"],
      select: { Site: true },
      where: { Site: { not: null } },
      orderBy: { Site: "asc" },
    });

    console.log(`✅ Found ${sites.length} unique sites`);

    return res.status(200).json({
      success: true,
      data: {
        total: sites.length,
        sites: sites.map((s: { Site: string | null }) => s.Site),
      },
    });
  } catch (error: any) {
    console.error("❌ Error fetching total sites:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

// ✅ Get Total Bands
export const getTotalBands = async (req: Request, res: Response) => {
  try {
    console.log("📡 Fetching total bands...");

    const bands = await prisma.uploadData.findMany({
      distinct: ["Band"],
      select: { Band: true },
      where: { Band: { not: null } },
      orderBy: { Band: "asc" },
    });

    console.log(`✅ Found ${bands.length} unique bands`);

    return res.status(200).json({
      success: true,
      data: {
        total: bands.length,
        bands: bands.map((b: { Band: string | null }) => b.Band),
      },
    });
  } catch (error: any) {
    console.error("❌ Error fetching total bands:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

// ✅ Get Total Tech
export const getTotalTech = async (req: Request, res: Response) => {
  try {
    console.log("🔧 Fetching total tech...");

    const tech = await prisma.uploadData.findMany({
      distinct: ["Tech"],
      select: { Tech: true },
      where: { Tech: { not: null } },
      orderBy: { Tech: "asc" },
    });

    console.log(`✅ Found ${tech.length} unique tech types`);

    return res.status(200).json({
      success: true,
      data: {
        total: tech.length,
        tech: tech.map((t: { Tech: string | null }) => t.Tech),
      },
    });
  } catch (error: any) {
    console.error("❌ Error fetching total tech:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

// ✅ Get Total Sectors
export const getTotalSectors = async (req: Request, res: Response) => {
  try {
    console.log("🎯 Fetching total sectors...");

    const sectors = await prisma.uploadData.findMany({
      distinct: ["Sector_ID"],
      select: { Sector_ID: true, Sector_Name: true },
      where: { Sector_ID: { not: null } },
      orderBy: { Sector_ID: "asc" },
    });

    console.log(`✅ Found ${sectors.length} unique sectors`);

    return res.status(200).json({
      success: true,
      data: {
        total: sectors.length,
        sectors: sectors.map((s: { Sector_ID: string | null; Sector_Name: string | null }) => ({
          id: s.Sector_ID,
          name: s.Sector_Name,
        })),
      },
    });
  } catch (error: any) {
    console.error("❌ Error fetching total sectors:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

// ✅ Get Total Groups
export const getTotalGroups = async (req: Request, res: Response) => {
  try {
    console.log("👥 Fetching total groups...");

    const groups = await prisma.uploadData.findMany({
      distinct: ["Groups"],
      select: { Groups: true },
      where: { Groups: { not: null } },
      orderBy: { Groups: "asc" },
    });

    console.log(`✅ Found ${groups.length} unique groups`);

    return res.status(200).json({
      success: true,
      data: {
        total: groups.length,
        groups: groups.map((g: { Groups: string | null }) => g.Groups),
      },
    });
  } catch (error: any) {
    console.error("❌ Error fetching total groups:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

// ✅ BONUS: Get Detailed Breakdown with Counts
export const getDetailedStats = async (req: Request, res: Response) => {
  try {
    console.log("📊 Fetching detailed statistics...");

    const startTime = Date.now();

    const [bandStats, techStats, groupStats, sectorStats] = await Promise.all([
      // Band distribution with count
      prisma.uploadData.groupBy({
        by: ["Band"],
        _count: { Band: true },
        where: { Band: { not: null } },
        orderBy: { _count: { Band: "desc" } },
      }),

      // Tech distribution with count
      prisma.uploadData.groupBy({
        by: ["Tech"],
        _count: { Tech: true },
        where: { Tech: { not: null } },
        orderBy: { _count: { Tech: "desc" } },
      }),

      // Groups distribution with count
      prisma.uploadData.groupBy({
        by: ["Groups"],
        _count: { Groups: true },
        where: { Groups: { not: null } },
        orderBy: { _count: { Groups: "desc" } },
      }),

      // Sectors distribution with count
      prisma.uploadData.groupBy({
        by: ["Sector_ID"],
        _count: { Sector_ID: true },
        where: { Sector_ID: { not: null } },
        orderBy: { _count: { Sector_ID: "desc" } },
      }),
    ]);

    const executionTime = Date.now() - startTime;

    console.log(`✅ Detailed stats fetched in ${executionTime}ms`);

    return res.status(200).json({
      success: true,
      data: {
        bandDistribution: bandStats.map((b: { Band: string | null; _count: { Band: number } }) => ({
          band: b.Band,
          count: b._count.Band,
        })),
        techDistribution: techStats.map((t: { Tech: string | null; _count: { Tech: number } }) => ({
          tech: t.Tech,
          count: t._count.Tech,
        })),
        groupDistribution: groupStats.map((g: { Groups: string | null; _count: { Groups: number } }) => ({
          group: g.Groups,
          count: g._count.Groups,
        })),
        sectorDistribution: sectorStats.map((s: { Sector_ID: string | null; _count: { Sector_ID: number } }) => ({
          sectorId: s.Sector_ID,
          count: s._count.Sector_ID,
        })),
      },
      meta: {
        executionTime: `${executionTime}ms`,
        timestamp: new Date().toISOString(),
      },
    });
  } catch (error: any) {
    console.error("❌ Error fetching detailed stats:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

// ✅ BONUS: Get Performance Metrics Summary
export const getPerformanceMetrics = async (req: Request, res: Response) => {
  try {
    console.log("Fetching performance metrics...");
    const parsedFileId = Number(req.query.fileId);
    const hasFileIdFilter = Number.isFinite(parsedFileId) && parsedFileId > 0;
    const dataWhere = hasFileIdFilter ? { fileId: parsedFileId } : undefined;

    const metrics = await prisma.uploadData.aggregate({
      where: dataWhere,
      _avg: {
        UL_PRB_Utilization_Rate: true,
        DL_PRB_Utilization_Rate: true,
        E_RAB_Drop_Rate: true,
        RRC_Drop_Rate: true,
        Initial_ERAB_Establishment_Success_Rate: true,
        RRC_Establishment_Success_Rate: true,
        E_RAB_Setup_Success_Rate: true,
        VOLTE_CSSR_Eric: true,
        VOLTE_DCR_Eric: true,
        Inter_Freq_HOSR: true,
        Intra_Freq_HOSR: true,
        CSFB_Success_Rate: true,
      },
      _max: {
        UL_PRB_Utilization_Rate: true,
        DL_PRB_Utilization_Rate: true,
        Mean_RRC_Connected_User_Number: true,
        Maximum_RRC_Connected_User_Number: true,
      },
      _min: {
        E_RAB_Drop_Rate: true,
        RRC_Drop_Rate: true,
      },
    });

    console.log("Performance metrics calculated");

    return res.status(200).json({
      success: true,
      data: {
        fileId: hasFileIdFilter ? parsedFileId : null,
        averages: {
          ulPrbUtilization: metrics._avg.UL_PRB_Utilization_Rate,
          dlPrbUtilization: metrics._avg.DL_PRB_Utilization_Rate,
          erabDropRate: metrics._avg.E_RAB_Drop_Rate,
          rrcDropRate: metrics._avg.RRC_Drop_Rate,
          erabSuccessRate: metrics._avg.Initial_ERAB_Establishment_Success_Rate,
          rrcSuccessRate: metrics._avg.RRC_Establishment_Success_Rate,
          erabSetupRate: metrics._avg.E_RAB_Setup_Success_Rate,
          volteCssr: metrics._avg.VOLTE_CSSR_Eric,
          volteDcr: metrics._avg.VOLTE_DCR_Eric,
          interFreqHosr: metrics._avg.Inter_Freq_HOSR,
          intraFreqHosr: metrics._avg.Intra_Freq_HOSR,
          csfbSuccessRate: metrics._avg.CSFB_Success_Rate,
        },
        peaks: {
          maxUlUtilization: metrics._max.UL_PRB_Utilization_Rate,
          maxDlUtilization: metrics._max.DL_PRB_Utilization_Rate,
          maxRrcUsers: metrics._max.Maximum_RRC_Connected_User_Number,
          minErabDropRate: metrics._min.E_RAB_Drop_Rate,
          minRrcDropRate: metrics._min.RRC_Drop_Rate,
        },
      },
    });
  } catch (error: any) {
    console.error("Error fetching performance metrics:", error);
    return res.status(500).json({
      success: false,
      message: error.message,
    });
  }
};

