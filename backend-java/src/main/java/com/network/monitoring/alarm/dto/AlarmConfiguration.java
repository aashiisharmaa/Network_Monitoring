package com.network.monitoring.alarm.dto;

import jakarta.validation.constraints.*;

/** API contracts kept separate from the legacy Company persistence model. */
public final class AlarmConfiguration {
    private AlarmConfiguration() {}

    public record Zone(long id, long companyId, String code, String name, boolean active, int version) {}
    public record Circle(long id, long companyId, long zoneId, String code, String name, boolean active, int version) {}
    public record Alias(long id, long companyId, long circleId, String alias, boolean canonical) {}
    public record Feed(long id, long companyId, String sourceKey, String name, String oem, String technology,
                       String fileFormat, String sheet, String sourceTimezone, String dumpType,
                       Long circleId, boolean active, int version) {}
    public record Resolution(String input, String status, Circle circle) {}
    public record ZoneCreate(@NotBlank @Size(max = 64) String code, @NotBlank @Size(max = 160) String name) {}
    public record ZoneUpdate(@NotBlank @Size(max = 160) String name, boolean active, @Min(1) int version) {}
    public record CircleCreate(@Positive long zoneId, @NotBlank @Size(max = 64) String code,
                               @NotBlank @Size(max = 160) String name) {}
    public record CircleUpdate(@Positive long zoneId, @NotBlank @Size(max = 160) String name,
                               boolean active, @Min(1) int version) {}
    public record AliasCreate(@Positive long circleId, @NotBlank @Size(max = 64) String alias) {}
    public record FeedCreate(@NotBlank @Size(max = 64) String sourceKey, @NotBlank @Size(max = 160) String name,
                             @NotBlank String oem, @NotBlank String technology, @NotBlank String fileFormat,
                             @Size(max = 128) String sheet, @NotBlank @Size(max = 64) String sourceTimezone,
                             @NotBlank String dumpType, @Positive Long circleId) {}
    public record FeedUpdate(@NotBlank @Size(max = 160) String name, @NotBlank String oem,
                             @NotBlank String technology, @NotBlank String fileFormat, @Size(max = 128) String sheet,
                             @NotBlank @Size(max = 64) String sourceTimezone, @NotBlank String dumpType,
                             @Positive Long circleId, boolean active, @Min(1) int version) {}
}
