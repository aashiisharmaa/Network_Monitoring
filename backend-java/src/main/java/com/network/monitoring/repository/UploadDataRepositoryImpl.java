package com.network.monitoring.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class UploadDataRepositoryImpl implements UploadDataRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Map<String, Integer> countAvailableMetrics(Long fileId) {
        String sql = "SELECT " +
                "SUM(UL_PRB_Utilization_Rate IS NOT NULL) AS UL_PRB_Utilization_Rate, " +
                "SUM(DL_PRB_Utilization_Rate IS NOT NULL) AS DL_PRB_Utilization_Rate, " +
                "SUM(UME_4G_Data_Volume_STD_MAPS_MB_903593_1 IS NOT NULL) AS UME_4G_Data_Volume_STD_MAPS_MB_903593_1, " +
                "SUM(UME_E_UTRAN_IP_Throughput_UE_UL_STD_Kbps IS NOT NULL) AS UME_E_UTRAN_IP_Throughput_UE_UL_STD_Kbps, " +
                "SUM(UME_E_UTRAN_IP_Throughput_UE_DL_STD_Kbps IS NOT NULL) AS UME_E_UTRAN_IP_Throughput_UE_DL_STD_Kbps, " +
                "SUM(E_RAB_Drop_Rate IS NOT NULL) AS E_RAB_Drop_Rate, " +
                "SUM(Initial_ERAB_Establishment_Success_Rate IS NOT NULL) AS Initial_ERAB_Establishment_Success_Rate, " +
                "SUM(RRC_Establishment_Success_Rate IS NOT NULL) AS RRC_Establishment_Success_Rate, " +
                "SUM(Mean_RRC_Connected_User_Number IS NOT NULL) AS Mean_RRC_Connected_User_Number, " +
                "SUM(Maximum_RRC_Connected_User_Number IS NOT NULL) AS Maximum_RRC_Connected_User_Number, " +
                "SUM(E_RAB_Setup_Success_Rate IS NOT NULL) AS E_RAB_Setup_Success_Rate, " +
                "SUM(RRC_Drop_Rate IS NOT NULL) AS RRC_Drop_Rate, " +
                "SUM(VOLTE_CSSR_Eric IS NOT NULL) AS VOLTE_CSSR_Eric, " +
                "SUM(VOLTE_DCR_Eric IS NOT NULL) AS VOLTE_DCR_Eric, " +
                "SUM(Inter_Freq_HOSR IS NOT NULL) AS Inter_Freq_HOSR, " +
                "SUM(Intra_Freq_HOSR IS NOT NULL) AS Intra_Freq_HOSR, " +
                "SUM(CSFB_Success_Rate IS NOT NULL) AS CSFB_Success_Rate " +
                "FROM UploadData ";

        if (fileId != null) {
            sql += "WHERE fileId = :fileId";
        }

        jakarta.persistence.Query query = entityManager.createNativeQuery(sql);
        if (fileId != null) {
            query.setParameter("fileId", fileId);
        }

        Object[] row = (Object[]) query.getSingleResult();
        Map<String, Integer> result = new HashMap<>();
        if (row != null && row.length > 0) {
            String[] keys = {
                    "UL_PRB_Utilization_Rate",
                    "DL_PRB_Utilization_Rate",
                    "UME_4G_Data_Volume_STD_MAPS_MB_903593_1",
                    "UME_E_UTRAN_IP_Throughput_UE_UL_STD_Kbps",
                    "UME_E_UTRAN_IP_Throughput_UE_DL_STD_Kbps",
                    "E_RAB_Drop_Rate",
                    "Initial_ERAB_Establishment_Success_Rate",
                    "RRC_Establishment_Success_Rate",
                    "Mean_RRC_Connected_User_Number",
                    "Maximum_RRC_Connected_User_Number",
                    "E_RAB_Setup_Success_Rate",
                    "RRC_Drop_Rate",
                    "VOLTE_CSSR_Eric",
                    "VOLTE_DCR_Eric",
                    "Inter_Freq_HOSR",
                    "Intra_Freq_HOSR",
                    "CSFB_Success_Rate"
            };
            for (int idx = 0; idx < keys.length; idx++) {
                Object value = idx < row.length ? row[idx] : null;
                result.put(keys[idx], value == null ? 0 : ((Number) value).intValue());
            }
        }
        return result;
    }
}
