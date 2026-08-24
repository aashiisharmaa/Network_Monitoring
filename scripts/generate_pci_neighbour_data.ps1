param(
  [Parameter(Mandatory = $true)]
  [string]$InputCsv,

  [string]$OutputDir = "reports",
  [double]$MaxDistanceKm = 8,
  [double]$AzimuthToleranceDeg = 80
)

$ErrorActionPreference = "Stop"

function Get-FirstValue {
  param([object]$Row, [string[]]$Names)
  foreach ($name in $Names) {
    if ($Row.PSObject.Properties.Name -contains $name) {
      $value = [string]$Row.$name
      if (-not [string]::IsNullOrWhiteSpace($value)) {
        return $value.Trim()
      }
    }
  }
  return ""
}

function To-DoubleOrNull {
  param([string]$Value)
  $number = 0.0
  if ([double]::TryParse($Value, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$number)) {
    return $number
  }
  return $null
}

function Get-DistanceKm {
  param([double]$Lat1, [double]$Lon1, [double]$Lat2, [double]$Lon2)
  $earthRadiusKm = 6371
  $dLat = ($Lat2 - $Lat1) * [Math]::PI / 180
  $dLon = ($Lon2 - $Lon1) * [Math]::PI / 180
  $rLat1 = $Lat1 * [Math]::PI / 180
  $rLat2 = $Lat2 * [Math]::PI / 180
  $a = [Math]::Pow([Math]::Sin($dLat / 2), 2) + [Math]::Cos($rLat1) * [Math]::Cos($rLat2) * [Math]::Pow([Math]::Sin($dLon / 2), 2)
  return $earthRadiusKm * 2 * [Math]::Atan2([Math]::Sqrt($a), [Math]::Sqrt(1 - $a))
}

function Get-BearingDeg {
  param([double]$Lat1, [double]$Lon1, [double]$Lat2, [double]$Lon2)
  $phi1 = $Lat1 * [Math]::PI / 180
  $phi2 = $Lat2 * [Math]::PI / 180
  $deltaLon = ($Lon2 - $Lon1) * [Math]::PI / 180
  $y = [Math]::Sin($deltaLon) * [Math]::Cos($phi2)
  $x = [Math]::Cos($phi1) * [Math]::Sin($phi2) - [Math]::Sin($phi1) * [Math]::Cos($phi2) * [Math]::Cos($deltaLon)
  return ([Math]::Atan2($y, $x) * 180 / [Math]::PI + 360) % 360
}

function Get-AngleDeltaDeg {
  param([double]$Left, [double]$Right)
  return [Math]::Abs((($Left - $Right + 540) % 360) - 180)
}

$rows = Import-Csv -LiteralPath $InputCsv
$cells = foreach ($row in $rows) {
  $lat = To-DoubleOrNull (Get-FirstValue $row @("Latitude", "lat", "site_latitude", "cell_latitude"))
  $lon = To-DoubleOrNull (Get-FirstValue $row @("Longitude", "lon", "lng", "site_longitude", "cell_longitude"))
  $azimuth = To-DoubleOrNull (Get-FirstValue $row @("Azimuth", "AZIMUTH", "azimuth"))
  $pci = Get-FirstValue $row @("Physical Cell ID [PCI]", "PCI", "pci", "physical_cell_id_pci", "physicalcellidpci")
  $siteId = Get-FirstValue $row @("4G Site Name", "SITEID", "Site ID", "siteId", "site_id", "Site Name")
  $cellName = Get-FirstValue $row @("4G Cell Name", "Cell_Name", "cellName", "cell_name")
  if ($lat -ne $null -and $lon -ne $null -and -not [string]::IsNullOrWhiteSpace($siteId) -and -not [string]::IsNullOrWhiteSpace($pci)) {
    [pscustomobject]@{
      SiteId = $siteId
      CellName = $cellName
      PCI = $pci
      Latitude = $lat
      Longitude = $lon
      Azimuth = $azimuth
    }
  }
}

$sites = $cells | Group-Object SiteId | ForEach-Object {
  $first = $_.Group[0]
  [pscustomobject]@{
    SiteId = $_.Name
    Latitude = [double]$first.Latitude
    Longitude = [double]$first.Longitude
    Cells = $_.Group
  }
}

$neighbours = New-Object System.Collections.Generic.List[object]
$risks = New-Object System.Collections.Generic.List[object]

for ($i = 0; $i -lt $sites.Count; $i++) {
  for ($j = 0; $j -lt $sites.Count; $j++) {
    if ($i -eq $j) { continue }
    $source = $sites[$i]
    $target = $sites[$j]
    $distance = Get-DistanceKm $source.Latitude $source.Longitude $target.Latitude $target.Longitude
    if ($distance -gt $MaxDistanceKm) { continue }

    $bearing = Get-BearingDeg $source.Latitude $source.Longitude $target.Latitude $target.Longitude
    $bestDelta = $null
    foreach ($cell in $source.Cells) {
      if ($cell.Azimuth -eq $null) { continue }
      $delta = Get-AngleDeltaDeg ([double]$cell.Azimuth) $bearing
      if ($bestDelta -eq $null -or $delta -lt $bestDelta) { $bestDelta = $delta }
    }
    $inferred = $distance -le 1 -or ($bestDelta -ne $null -and $bestDelta -le $AzimuthToleranceDeg)
    if (-not $inferred) { continue }

    $sourcePcis = @($source.Cells | Select-Object -ExpandProperty PCI -Unique)
    $targetPcis = @($target.Cells | Select-Object -ExpandProperty PCI -Unique)
    $samePcis = @($sourcePcis | Where-Object { $targetPcis -contains $_ })
    $samePciTargetCells = @($target.Cells | Where-Object { $sourcePcis -contains $_.PCI })

    $neighbours.Add([pscustomobject]@{
      SourceSite = $source.SiteId
      NeighbourSite = $target.SiteId
      DistanceKm = [Math]::Round($distance, 3)
      BearingDeg = [Math]::Round($bearing, 1)
      BestSourceAzimuthDeltaDeg = if ($bestDelta -eq $null) { "" } else { [Math]::Round($bestDelta, 1) }
      SamePciList = ($samePcis -join "|")
      SamePciCount = $samePcis.Count
      SamePciTargetCellCount = $samePciTargetCells.Count
      SamePciTargetCells = (($samePciTargetCells | Select-Object -ExpandProperty CellName) -join "|")
      RelationType = if ($samePcis.Count -gt 0) { "INFERRED_NEIGHBOUR_SAME_PCI" } else { "INFERRED_NEIGHBOUR" }
    })
  }
}

$neighboursBySourceAndPci = $neighbours |
  Where-Object { $_.SamePciCount -gt 0 } |
  ForEach-Object {
    $neighbourRow = $_
    foreach ($pci in ($neighbourRow.SamePciList -split "\|" | Where-Object { $_ })) {
      $samePciTargetCells = @(
        $sites |
          Where-Object { $_.SiteId -eq $neighbourRow.NeighbourSite } |
          Select-Object -ExpandProperty Cells |
          Where-Object { $_.PCI -eq $pci }
      )
      [pscustomobject]@{
        SourceSite = $neighbourRow.SourceSite
        PCI = $pci
        NeighbourSite = $neighbourRow.NeighbourSite
        DistanceKm = $neighbourRow.DistanceKm
        SamePciTargetCells = $neighbourRow.SamePciTargetCells
        SamePciTargetCellCount = $samePciTargetCells.Count
      }
    }
  } |
  Group-Object SourceSite, PCI

foreach ($group in $neighboursBySourceAndPci) {
  $items = @($group.Group)
  $sourceSite = $items[0].SourceSite
  $pci = $items[0].PCI
  $targetCellCount = ($items | Measure-Object SamePciTargetCellCount -Sum).Sum
  $riskType = if ($targetCellCount -ge 2) { "PCI_CONFUSION" } else { "PCI_COLLISION" }
  $risks.Add([pscustomobject]@{
    SourceSite = $sourceSite
    PCI = $pci
    RiskType = $riskType
    InferredSamePciNeighbourSiteCount = $items.Count
    InferredSamePciNeighbourCellCount = $targetCellCount
    NeighbourSites = (($items | Sort-Object DistanceKm | Select-Object -ExpandProperty NeighbourSite) -join "|")
    NeighbourCells = (($items | ForEach-Object { $_.SamePciTargetCells -split "\|" } | Where-Object { $_ } | Select-Object -Unique) -join "|")
    NearestDistanceKm = ($items | Measure-Object DistanceKm -Minimum).Minimum
    Evidence = if ($riskType -eq "PCI_CONFUSION") { "Source has multiple inferred neighbour cells using the same PCI." } else { "Source has one inferred neighbour cell using the same PCI." }
  })
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$safeName = [IO.Path]::GetFileNameWithoutExtension($InputCsv) -replace "[^a-zA-Z0-9_-]", "_"
$neighbourPath = Join-Path $OutputDir "$safeName`_inferred_neighbours.csv"
$riskPath = Join-Path $OutputDir "$safeName`_pci_collision_confusion.csv"
$neighbours | Sort-Object SourceSite, DistanceKm | Export-Csv -LiteralPath $neighbourPath -NoTypeInformation
$risks | Sort-Object RiskType, SourceSite, PCI | Export-Csv -LiteralPath $riskPath -NoTypeInformation

[pscustomobject]@{
  InputRows = $rows.Count
  ParsedCells = $cells.Count
  Sites = $sites.Count
  InferredNeighbourRows = $neighbours.Count
  PciRiskRows = $risks.Count
  NeighbourFile = $neighbourPath
  PciRiskFile = $riskPath
}
