import React, { useCallback, useEffect, useState, useMemo, useRef } from "react";
import { GoogleMap, useJsApiLoader } from "@react-google-maps/api";
import { getMapDetails } from "./MapService";
import {
  Radio,
  MapPin,
  Signal,
  Compass,
  Antenna,
  Building2,
  Hash,
  Waves,
  Loader2,
  Search,
  Filter,
  X,
  Layers,
  TrendingUp,
  Activity,
  Eye,
  EyeOff,
} from "lucide-react";

export default function MapPage() {
  const [mapData, setMapData] = useState([]);
  const [map, setMap] = useState(null);
  const [selectedSite, setSelectedSite] = useState(null);
  const [selectedCell, setSelectedCell] = useState(null);
  const [loading, setLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState("");
  const [sidebarOpen, setSidebarOpen] = useState(true);
  const [hoveredSite, setHoveredSite] = useState(null);
  const [zoomLevel, setZoomLevel] = useState(11);
  const [showCells, setShowCells] = useState(false);

  // Refs for performance
  const cellPolygonsRef = useRef(new Map());
  const siteMarkersRef = useRef(new Map());
  const infoWindowRef = useRef(null);
  const zoomTimeoutRef = useRef(null);
  const miniTooltipRef = useRef(null); // ADDED: Track mini tooltip

  const containerStyle = {
    width: "100%",
    height: "100vh",
  };

  // Fetch map data
  useEffect(() => {
    const fetchData = async () => {
      try {
        setLoading(true);
        const response = await getMapDetails();
        if (response.success && response.data) {
          setMapData(response.data);
        }
      } catch (error) {
        console.error("Error fetching map data:", error);
      } finally {
        setLoading(false);
      }
    };
    fetchData();
  }, []);

  // Memoize grouped sites
  const groupedSites = useMemo(() => {
    return mapData.reduce((acc, item) => {
      if (!acc[item.SITEID]) {
        acc[item.SITEID] = {
          ...item,
          cells: [item],
        };
      } else {
        acc[item.SITEID].cells.push(item);
      }
      return acc;
    }, {});
  }, [mapData]);

  const uniqueSites = useMemo(() => Object.values(groupedSites), [groupedSites]);

  // Filter sites based on search
  const filteredSites = useMemo(() => {
    if (!searchTerm) return uniqueSites;
    const lowerSearch = searchTerm.toLowerCase();
    return uniqueSites.filter(
      (site) =>
        site.Site_Name?.toLowerCase().includes(lowerSearch) ||
        site.SITEID?.toString().includes(searchTerm)
    );
  }, [uniqueSites, searchTerm]);

  const center = { lat: -33.9, lng: 18.65 };

  const { isLoaded } = useJsApiLoader({
    id: "google-map-script",
    googleMapsApiKey: import.meta.env.VITE_GOOGLE_MAPS_API_KEY,
    mapIds: [import.meta.env.VITE_GOOGLE_MAP_ID],
  });

  // Debounced zoom handler
  const handleZoomChange = useCallback(() => {
    if (map) {
      if (zoomTimeoutRef.current) {
        clearTimeout(zoomTimeoutRef.current);
      }
      
      zoomTimeoutRef.current = setTimeout(() => {
        const newZoom = map.getZoom();
        setZoomLevel(newZoom);
      }, 200);
    }
  }, [map]);

  const onLoad = useCallback((map) => {
    setMap(map);
    
    // Create single info window instance
    if (window.google && !infoWindowRef.current) {
      infoWindowRef.current = new window.google.maps.InfoWindow({
        pixelOffset: new window.google.maps.Size(0, -10)
      });
    }
    
    map.addListener('zoom_changed', handleZoomChange);
  }, [handleZoomChange]);

  const onUnmount = useCallback(() => {
    cellPolygonsRef.current.forEach((polygon) => polygon.setMap(null));
    siteMarkersRef.current.forEach((marker) => marker.setMap(null));
    cellPolygonsRef.current.clear();
    siteMarkersRef.current.clear();
    
    if (infoWindowRef.current) {
      infoWindowRef.current.close();
    }
    
    if (miniTooltipRef.current) {
      miniTooltipRef.current.close();
      miniTooltipRef.current = null;
    }
    
    if (zoomTimeoutRef.current) {
      clearTimeout(zoomTimeoutRef.current);
    }
    
    setMap(null);
  }, []);

  // MODIFIED: Much smaller radius values for compact triangles
  const getBaseRadiusByZoom = useCallback((zoom) => {
    const scaleFactors = {
      8: 1200,   
      9: 900,    
      10: 650,   
      11: 500,   
      12: 380,   
      13: 280,   
      14: 200,   
      15: 140,   
      16: 100,   
      17: 70,    
      18: 50,    
      19: 35,    
      20: 25,    
      21: 18,    
      22: 12,    
    };
    
    const roundedZoom = Math.round(zoom);
    
    if (scaleFactors[roundedZoom]) {
      return scaleFactors[roundedZoom];
    }
    
    if (roundedZoom > 22) return 10;
    if (roundedZoom < 8) return 1500;
    
    return 400;
  }, []);

  const destinationPoint = useCallback((lat, lng, bearing, distance) => {
    const R = 6371e3;
    const δ = distance / R;
    const θ = (bearing * Math.PI) / 180;
    const φ1 = (lat * Math.PI) / 180;
    const λ1 = (lng * Math.PI) / 180;

    const φ2 = Math.asin(
      Math.sin(φ1) * Math.cos(δ) + Math.cos(φ1) * Math.sin(δ) * Math.cos(θ)
    );

    const λ2 =
      λ1 +
      Math.atan2(
        Math.sin(θ) * Math.sin(δ) * Math.cos(φ1),
        Math.cos(δ) - Math.sin(φ1) * Math.sin(φ2)
      );

    return {
      lat: (φ2 * 180) / Math.PI,
      lng: (λ2 * 180) / Math.PI,
    };
  }, []);

  const createCellTriangle = useCallback((lat, lng, azimuth, radius, beamWidth = 65) => {
    const apex = { lat, lng };
    const baseCenter = destinationPoint(lat, lng, azimuth, radius);
    const halfWidth = radius * Math.tan(((beamWidth / 2) * Math.PI) / 180);

    const leftBase = destinationPoint(
      baseCenter.lat,
      baseCenter.lng,
      azimuth - 90,
      halfWidth
    );

    const rightBase = destinationPoint(
      baseCenter.lat,
      baseCenter.lng,
      azimuth + 90,
      halfWidth
    );

    return [apex, rightBase, leftBase];
  }, [destinationPoint]);

const extractCellLayer = useCallback((cellName) => {
  if (!cellName) return 1; // handle null/undefined

  const match = cellName.match(/[A-Z](\d+)$/);
  return match ? parseInt(match[1]) : 1;
}, []);

const extractSector = useCallback((cellName) => {
  if (!cellName) return "A"; // handle null/undefined

  const match = cellName.match(/([A-Z])\d+$/);
  return match ? match[1] : "A";
}, []);

  const getLayerMultiplier = useCallback((layer) => {
    const multipliers = { 
      1: 0.35,
      2: 0.50,  
      5: 0.70,  
      6: 0.85,  
      9: 1.0
    };
    return multipliers[layer] || 0.60;
  }, []);

  const getZIndexByLayer = useCallback((layer) => {
    const zIndexMap = { 9: 1, 6: 4, 5: 5, 2: 8, 1: 10 };
    return zIndexMap[layer] || 5;
  }, []);

  const getColorBySector = useCallback((sector, layer) => {
    const sectorColors = {
      A: { fill: "#60A5FA", stroke: "#3B82F6", light: "#DBEAFE", glow: "#93C5FD" },
      B: { fill: "#34D399", stroke: "#10B981", light: "#D1FAE5", glow: "#6EE7B7" },
      C: { fill: "#FB923C", stroke: "#F97316", light: "#FFEDD5", glow: "#FDBA74" },
      D: { fill: "#F472B6", stroke: "#EC4899", light: "#FCE7F3", glow: "#F9A8D4" },
      E: { fill: "#A78BFA", stroke: "#8B5CF6", light: "#EDE9FE", glow: "#C4B5FD" },
      F: { fill: "#FCD34D", stroke: "#F59E0B", light: "#FEF3C7", glow: "#FDE68A" },
    };

    const baseColor = sectorColors[sector] || sectorColors.A;
    const opacityMap = { 9: 0.15, 6: 0.20, 5: 0.25, 2: 0.30, 1: 0.35 };

    return {
      ...baseColor,
      opacity: opacityMap[layer] || 0.25,
    };
  }, []);

  const getFrequencyBand = useCallback((freq) => {
    if (freq < 1000) return { name: "Low Band", color: "#A78BFA" };
    if (freq < 2000) return { name: "Mid Band", color: "#60A5FA" };
    if (freq < 2500) return { name: "High Mid", color: "#34D399" };
    return { name: "High Band", color: "#FB923C" };
  }, []);

  const createCellInfoWindow = useCallback((cell) => {
    const sector = extractSector(cell.Cell_Name);
    const layer = extractCellLayer(cell.Cell_Name);
    const colors = getColorBySector(sector, layer);
    const freqBand = getFrequencyBand(cell.Downlink_Center_Frequency);

    return `
      <div style="padding: 0; width: 340px; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; border-radius: 12px; overflow: hidden; background: white;">
        <div style="background: linear-gradient(135deg, ${colors.fill}20 0%, ${colors.light} 100%); padding: 20px; border-bottom: 3px solid ${colors.fill};">
          <div style="display: flex; align-items: center; gap: 12px; margin-bottom: 12px;">
            <div style="width: 40px; height: 40px; background: ${colors.fill}; border-radius: 10px; display: flex; align-items: center; justify-content: center; box-shadow: 0 4px 12px ${colors.fill}40;">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2.5">
                <path d="M2 12 7 2M17 2l5 10M12 12v10M5 12h14"></path>
              </svg>
            </div>
            <div style="flex: 1;">
              <h3 style="margin: 0; font-size: 18px; font-weight: 700; color: #1F2937; letter-spacing: -0.3px;">
                ${cell.Cell_Name}
              </h3>
              <p style="margin: 4px 0 0 0; font-size: 12px; color: #6B7280; font-weight: 500;">
                Site ${cell.SITEID}
              </p>
            </div>
          </div>
          
          <div style="display: flex; gap: 6px;">
            <span style="background: white; padding: 4px 10px; border-radius: 6px; font-size: 11px; font-weight: 600; color: ${colors.stroke}; box-shadow: 0 1px 3px rgba(0,0,0,0.1);">
              Sector ${sector}
            </span>
            <span style="background: white; padding: 4px 10px; border-radius: 6px; font-size: 11px; font-weight: 600; color: #6B7280; box-shadow: 0 1px 3px rgba(0,0,0,0.1);">
              Layer ${layer}
            </span>
            <span style="background: white; padding: 4px 10px; border-radius: 6px; font-size: 11px; font-weight: 600; color: ${freqBand.color}; box-shadow: 0 1px 3px rgba(0,0,0,0.1);">
              ${freqBand.name}
            </span>
          </div>
        </div>
        
        <div style="padding: 20px;">
          <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 16px;">
            <div style="background: ${colors.light}; padding: 14px; border-radius: 10px; border-left: 3px solid ${colors.fill};">
              <div style="font-size: 10px; color: ${colors.stroke}; font-weight: 600; text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 6px;">Azimuth</div>
              <div style="font-size: 26px; color: #1F2937; font-weight: 800; font-family: 'SF Mono', monospace;">
                ${cell.AZIMUTH}<span style="font-size: 14px; color: #9CA3AF;">°</span>
              </div>
            </div>
            
            <div style="background: #F3F4F6; padding: 14px; border-radius: 10px; border-left: 3px solid #6B7280;">
              <div style="font-size: 10px; color: #6B7280; font-weight: 600; text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 6px;">Frequency</div>
              <div style="font-size: 20px; color: #1F2937; font-weight: 800; font-family: 'SF Mono', monospace;">
                ${cell.Downlink_Center_Frequency}<span style="font-size: 11px; color: #9CA3AF;"> MHz</span>
              </div>
            </div>
          </div>
          
          <div style="display: grid; gap: 8px;">
            <div style="display: flex; justify-content: space-between; padding: 10px 12px; background: #F9FAFB; border-radius: 8px;">
              <span style="font-size: 12px; color: #6B7280; font-weight: 500;">PCI</span>
              <span style="font-size: 13px; color: #1F2937; font-weight: 700;">${cell.PCI}</span>
            </div>
            <div style="display: flex; justify-content: space-between; padding: 10px 12px; background: #F9FAFB; border-radius: 8px;">
              <span style="font-size: 12px; color: #6B7280; font-weight: 500;">TAC</span>
              <span style="font-size: 13px; color: #1F2937; font-weight: 700;">${cell.TAC}</span>
            </div>
            <div style="display: flex; justify-content: space-between; padding: 10px 12px; background: #F9FAFB; border-radius: 8px;">
              <span style="font-size: 12px; color: #6B7280; font-weight: 500;">Antenna Height</span>
              <span style="font-size: 13px; color: #1F2937; font-weight: 700;">${cell.Antenna_Height}m</span>
            </div>
            <div style="display: flex; justify-content: space-between; padding: 10px 12px; background: #F9FAFB; border-radius: 8px;">
              <span style="font-size: 12px; color: #6B7280; font-weight: 500;">Tilt (E / M)</span>
              <span style="font-size: 13px; color: #1F2937; font-weight: 700;">${cell.E_tilt}° / ${cell.M_tilt}°</span>
            </div>
            <div style="display: flex; justify-content: space-between; padding: 10px 12px; background: #F9FAFB; border-radius: 8px;">
              <span style="font-size: 12px; color: #6B7280; font-weight: 500;">Coordinates</span>
              <span style="font-size: 11px; color: #1F2937; font-weight: 600; font-family: monospace;">${cell.lat.toFixed(5)}, ${cell.lon.toFixed(5)}</span>
            </div>
          </div>
        </div>
        
        <div style="background: #F9FAFB; padding: 12px 20px; border-top: 1px solid #E5E7EB;">
          <div style="font-size: 11px; color: #9CA3AF; text-align: center;">
            Cell ID: <strong style="color: #6B7280;">${cell.Cell_ID}</strong>
          </div>
        </div>
      </div>
    `;
  }, [extractSector, extractCellLayer, getColorBySector, getFrequencyBand]);

  // Render site markers
  useEffect(() => {
    if (!map || !window.google || filteredSites.length === 0) return;

    const bounds = new window.google.maps.LatLngBounds();

    filteredSites.forEach((site) => {
      const markerKey = site.SITEID;
      let marker = siteMarkersRef.current.get(markerKey);

      if (!marker) {
        marker = new window.google.maps.Marker({
          position: { lat: site.lat, lng: site.lon },
          map: map,
          icon: {
            path: window.google.maps.SymbolPath.CIRCLE,
            scale: 10,
            fillColor: "#FFFFFF",
            fillOpacity: 1,
            strokeColor: "#3B82F6",
            strokeWeight: 3,
          },
          title: site.Site_Name,
          zIndex: 100000,
        });

        marker.siteData = site;

        marker.addListener("mouseover", () => {
          marker.setIcon({
            path: window.google.maps.SymbolPath.CIRCLE,
            scale: 12,
            fillColor: "#3B82F6",
            fillOpacity: 1,
            strokeColor: "#FFFFFF",
            strokeWeight: 3,
          });

          const tooltip = new window.google.maps.InfoWindow({
            content: `
              <div style="padding: 8px 12px; font-family: -apple-system, system-ui; background: white; border-radius: 8px; font-weight: 600; font-size: 13px;">
                <div style="color: #1F2937; margin-bottom: 4px;">${site.Site_Name}</div>
                <div style="color: #6B7280; font-size: 11px; font-weight: 500;">
                  Site ID: ${site.SITEID} • ${site.cells.length} cells
                </div>
              </div>
            `,
            position: { lat: site.lat, lng: site.lon },
            pixelOffset: new window.google.maps.Size(0, -20),
          });
          marker.tooltip = tooltip;
          tooltip.open(map);
        });

        marker.addListener("mouseout", () => {
          if (selectedSite?.SITEID !== site.SITEID) {
            marker.setIcon({
              path: window.google.maps.SymbolPath.CIRCLE,
              scale: 10,
              fillColor: "#FFFFFF",
              fillOpacity: 1,
              strokeColor: "#3B82F6",
              strokeWeight: 3,
            });
          }
          if (marker.tooltip) {
            marker.tooltip.close();
            marker.tooltip = null;
          }
        });

        marker.addListener("click", () => {
          handleSiteMarkerClick(site);
        });

        siteMarkersRef.current.set(markerKey, marker);
      } else {
        marker.setMap(map);
      }

      bounds.extend({ lat: site.lat, lng: site.lon });
    });

    siteMarkersRef.current.forEach((marker, key) => {
      if (!filteredSites.find(site => site.SITEID === key)) {
        marker.setMap(null);
        siteMarkersRef.current.delete(key);
      }
    });

    if (siteMarkersRef.current.size > 0 && zoomLevel === 11) {
      map.fitBounds(bounds);
    }
  }, [map, filteredSites]);

  const handleSiteMarkerClick = useCallback((site) => {
    setSelectedSite(site);
    
    siteMarkersRef.current.forEach((marker, key) => {
      if (key === site.SITEID) {
        marker.setIcon({
          path: window.google.maps.SymbolPath.CIRCLE,
          scale: 12,
          fillColor: "#3B82F6",
          fillOpacity: 1,
          strokeColor: "#FFFFFF",
          strokeWeight: 3,
        });
      } else {
        marker.setIcon({
          path: window.google.maps.SymbolPath.CIRCLE,
          scale: 10,
          fillColor: "#FFFFFF",
          fillOpacity: 1,
          strokeColor: "#3B82F6",
          strokeWeight: 3,
        });
      }
    });

    if (map) {
      map.panTo({ lat: site.lat, lng: site.lon });
      map.setZoom(Math.max(zoomLevel, 14));
    }
  }, [map, zoomLevel]);

  // Render cells
  useEffect(() => {
    if (!map || !window.google) return;

    cellPolygonsRef.current.forEach((polygon) => polygon.setMap(null));
    cellPolygonsRef.current.clear();

    if (!showCells) return;

    const sitesToShow = filteredSites;

    if (sitesToShow.length === 0) return;

    const baseRadius = getBaseRadiusByZoom(zoomLevel);

    sitesToShow.forEach((site) => {
      const sortedCells = [...site.cells].sort((a, b) => {
        const layerA = extractCellLayer(a.Cell_Name);
        const layerB = extractCellLayer(b.Cell_Name);
        return layerB - layerA;
      });

      sortedCells.forEach((cell) => {
        const cellKey = cell.Cell_ID;
        const layer = extractCellLayer(cell.Cell_Name);
        const sector = extractSector(cell.Cell_Name);
        const layerMultiplier = getLayerMultiplier(layer);
        const radius = baseRadius * layerMultiplier;
        const colors = getColorBySector(sector, layer);
        const zIndex = getZIndexByLayer(layer);

        const vertices = createCellTriangle(
          cell.lat,
          cell.lon,
          cell.AZIMUTH,
          radius,
          65
        );

        const polygon = new window.google.maps.Polygon({
          paths: vertices,
          strokeColor: colors.stroke,
          strokeOpacity: 0.8,
          strokeWeight: 1.5,
          fillColor: colors.fill,
          fillOpacity: colors.opacity,
          map: map,
          zIndex: zIndex,
          clickable: true,
        });

        polygon.cellData = cell;

        // MODIFIED: Click listener - close any open mini tooltip first
        polygon.addListener("click", (event) => {
          // Close any existing mini tooltip
          if (miniTooltipRef.current) {
            miniTooltipRef.current.close();
            miniTooltipRef.current = null;
          }

          // Close all mini tooltips on polygons
          cellPolygonsRef.current.forEach((p) => {
            if (p.miniTooltip) {
              p.miniTooltip.close();
              p.miniTooltip = null;
            }
          });

          infoWindowRef.current.setContent(createCellInfoWindow(cell));
          infoWindowRef.current.setPosition(event.latLng);
          infoWindowRef.current.open(map);
          setSelectedCell(cell);

          cellPolygonsRef.current.forEach((p) => {
            const pLayer = extractCellLayer(p.cellData.Cell_Name);
            const pSector = extractSector(p.cellData.Cell_Name);
            const pColors = getColorBySector(pSector, pLayer);
            
            if (p === polygon) {
              p.setOptions({
                strokeWeight: 3,
                fillOpacity: pColors.opacity + 0.2,
                zIndex: 10000,
              });
            } else {
              p.setOptions({
                strokeWeight: 1.5,
                fillOpacity: pColors.opacity,
                zIndex: getZIndexByLayer(pLayer),
              });
            }
          });
        });

        // MODIFIED: Hover effect - close mini tooltip if info window is open
        polygon.addListener("mouseover", () => {
          // Don't show mini tooltip if the main info window is open
          if (infoWindowRef.current && infoWindowRef.current.getMap()) {
            return;
          }

          polygon.setOptions({
            strokeWeight: 2.5,
            fillOpacity: colors.opacity + 0.15,
            zIndex: 9999,
          });

          const tooltip = new window.google.maps.InfoWindow({
            content: `
              <div style="padding: 6px 12px; font-family: -apple-system, system-ui; background: white; color: ${colors.stroke}; border-radius: 8px; font-weight: 600; font-size: 12px; border: 2px solid ${colors.fill}; box-shadow: 0 4px 12px rgba(0,0,0,0.15);">
                ${cell.Cell_Name} <span style="color: #9CA3AF;">•</span> ${cell.AZIMUTH}°
              </div>
            `,
            position: vertices[0],
            pixelOffset: new window.google.maps.Size(0, -10),
          });

          polygon.miniTooltip = tooltip;
          miniTooltipRef.current = tooltip;
          tooltip.open(map);
        });

        polygon.addListener("mouseout", () => {
          if (selectedCell?.Cell_Name !== cell.Cell_Name) {
            polygon.setOptions({
              strokeWeight: 1.5,
              fillOpacity: colors.opacity,
              zIndex: zIndex,
            });
          }

          if (polygon.miniTooltip) {
            polygon.miniTooltip.close();
            polygon.miniTooltip = null;
            if (miniTooltipRef.current === polygon.miniTooltip) {
              miniTooltipRef.current = null;
            }
          }
        });

        cellPolygonsRef.current.set(cellKey, polygon);
      });
    });
  }, [map, showCells, zoomLevel, filteredSites, getBaseRadiusByZoom, extractCellLayer, extractSector, getLayerMultiplier, getColorBySector, getZIndexByLayer, createCellTriangle, createCellInfoWindow]);

  const handleSiteClick = useCallback((site) => {
    handleSiteMarkerClick(site);
  }, [handleSiteMarkerClick]);

  if (loading) {
    return (
      <div className="h-screen w-full flex items-center justify-center bg-gradient-to-br from-blue-50 to-indigo-50">
        <div className="text-center">
          <Loader2 className="w-16 h-16 text-blue-500 animate-spin mx-auto mb-4" />
          <p className="text-gray-800 text-lg font-semibold">
            Loading Network Data...
          </p>
          <p className="text-gray-500 text-sm mt-2">
            Fetching cell tower information
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="h-screen w-full flex overflow-hidden bg-gray-50">
      {/* Sidebar */}
      <div
        className={`${
          sidebarOpen ? "w-96" : "w-0"
        } transition-all duration-300 ease-in-out bg-white shadow-lg overflow-hidden flex flex-col z-10 border-r border-gray-200`}
      >
        {/* Sidebar Header */}
        <div className="bg-gradient-to-r from-blue-500 to-indigo-500 p-6 text-white">
          <div className="flex items-center justify-between mb-4">
            <div className="flex items-center gap-3">
              <div className="p-2 bg-white/20 rounded-lg backdrop-blur-sm">
                <Radio className="w-6 h-6" />
              </div>
              <div>
                <h1 className="text-xl font-bold">Coverage Map</h1>
                <p className="text-blue-100 text-xs">Network Visualization</p>
              </div>
            </div>
            <button
              onClick={() => setSidebarOpen(false)}
              className="p-2 hover:bg-white/20 rounded-lg transition-colors"
            >
              <X className="w-5 h-5" />
            </button>
          </div>

          {/* Stats */}
          <div className="grid grid-cols-2 gap-3">
            <div className="bg-white/15 backdrop-blur-sm rounded-lg p-3">
              <div className="flex items-center gap-2 mb-1">
                <Building2 className="w-4 h-4 text-blue-100" />
                <span className="text-xs text-blue-100 font-medium">Sites</span>
              </div>
              <p className="text-2xl font-bold">{uniqueSites.length}</p>
            </div>
            <div className="bg-white/15 backdrop-blur-sm rounded-lg p-3">
              <div className="flex items-center gap-2 mb-1">
                <Signal className="w-4 h-4 text-blue-100" />
                <span className="text-xs text-blue-100 font-medium">Cells</span>
              </div>
              <p className="text-2xl font-bold">{mapData.length}</p>
            </div>
          </div>
        </div>

        {/* Search */}
        <div className="p-4 border-b border-gray-200 bg-gray-50">
          <div className="relative">
            <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 w-4 h-4 text-gray-400" />
            <input
              type="text"
              placeholder="Search sites..."
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="w-full pl-10 pr-4 py-2.5 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none text-sm bg-white"
            />
          </div>
        </div>

        {/* Site List */}
        <div className="flex-1 overflow-y-auto">
          {filteredSites.length === 0 ? (
            <div className="flex flex-col items-center justify-center h-full text-gray-400 p-8">
              <MapPin className="w-12 h-12 mb-3" />
              <p className="text-sm font-medium">No sites found</p>
            </div>
          ) : (
            <div className="p-3 space-y-2">
              {filteredSites.map((site) => {
                const cellsBySector = site.cells.reduce((acc, cell) => {
                  const sector = extractSector(cell.Cell_Name);
                  if (!acc[sector]) acc[sector] = [];
                  acc[sector].push(cell);
                  return acc;
                }, {});

                const sectors = Object.keys(cellsBySector).sort();

                return (
                  <button
                    key={site.SITEID}
                    onClick={() => handleSiteClick(site)}
                    onMouseEnter={() => setHoveredSite(site.SITEID)}
                    onMouseLeave={() => setHoveredSite(null)}
                    className={`w-full text-left p-4 rounded-xl transition-all duration-200 border ${
                      selectedSite?.SITEID === site.SITEID
                        ? "bg-blue-50 border-blue-300 shadow-sm"
                        : hoveredSite === site.SITEID
                        ? "bg-gray-50 border-gray-300 shadow-sm"
                        : "bg-white border-gray-200 hover:border-gray-300"
                    }`}
                  >
                    <div className="flex items-start justify-between mb-2">
                      <div className="flex-1">
                        <h3 className="font-semibold text-gray-900 text-sm mb-1 line-clamp-1">
                          {site.Site_Name}
                        </h3>
                        <div className="flex items-center gap-2 text-xs text-gray-500">
                          <Hash className="w-3 h-3" />
                          <span>{site.SITEID}</span>
                        </div>
                      </div>
                      <div
                        className={`p-2 rounded-lg ${
                          selectedSite?.SITEID === site.SITEID
                            ? "bg-blue-100"
                            : "bg-gray-100"
                        }`}
                      >
                        <Radio
                          className={`w-4 h-4 ${
                            selectedSite?.SITEID === site.SITEID
                              ? "text-blue-600"
                              : "text-gray-600"
                          }`}
                        />
                      </div>
                    </div>

                    <div className="flex items-center gap-2 mt-3 mb-3">
                      {sectors.map((sector) => {
                        const colors = getColorBySector(sector, 1);
                        return (
                          <div
                            key={sector}
                            className="w-7 h-7 rounded-lg text-xs font-bold text-white flex items-center justify-center shadow-sm"
                            style={{ backgroundColor: colors.fill }}
                          >
                            {sector}
                          </div>
                        );
                      })}
                    </div>

                    <div className="grid grid-cols-3 gap-2">
                      <div className="bg-blue-50 rounded-lg p-2 text-center">
                        <div className="text-xs text-blue-600 font-medium mb-0.5">
                          Cells
                        </div>
                        <div className="text-sm font-bold text-blue-900">
                          {site.cells.length}
                        </div>
                      </div>
                      <div className="bg-emerald-50 rounded-lg p-2 text-center">
                        <div className="text-xs text-emerald-600 font-medium mb-0.5">
                          Height
                        </div>
                        <div className="text-sm font-bold text-emerald-900">
                          {site.Antenna_Height}m
                        </div>
                      </div>
                      <div className="bg-violet-50 rounded-lg p-2 text-center">
                        <div className="text-xs text-violet-600 font-medium mb-0.5">
                          Sectors
                        </div>
                        <div className="text-sm font-bold text-violet-900">
                          {sectors.length}
                        </div>
                      </div>
                    </div>
                  </button>
                );
              })}
            </div>
          )}
        </div>
      </div>

      {/* Main Map Area */}
      <div className="flex-1 relative">
        {!sidebarOpen && (
          <button
            onClick={() => setSidebarOpen(true)}
            className="absolute top-6 left-6 z-10 bg-white shadow-lg rounded-xl p-3 hover:bg-gray-50 transition-all duration-200 border border-gray-200"
          >
            <Filter className="w-5 h-5 text-gray-700" />
          </button>
        )}

        {/* Map Controls */}
        <div className="absolute top-6 right-6 z-10 flex flex-col gap-3">
          {/* Toggle Cells Visibility */}
          <button
            onClick={() => setShowCells(!showCells)}
            className={`bg-white/95 backdrop-blur-sm shadow-lg rounded-xl p-4 border-2 transition-all hover:shadow-xl ${
              showCells 
                ? 'border-blue-500 bg-blue-50' 
                : 'border-gray-200 hover:border-gray-300'
            }`}
          >
            <div className="flex items-center gap-3">
              {showCells ? (
                <Eye className="w-5 h-5 text-blue-600" />
              ) : (
                <EyeOff className="w-5 h-5 text-gray-500" />
              )}
              <div className="text-left">
                <div className={`text-sm font-bold ${showCells ? 'text-blue-900' : 'text-gray-900'}`}>
                  {showCells ? 'Hide Cells' : 'Show Cells'}
                </div>
                <div className="text-xs text-gray-500">
                  {showCells ? 'Coverage visible' : 'Coverage hidden'}
                </div>
              </div>
            </div>
          </button>

          {/* Coverage Stats */}
          <div className="bg-white/95 backdrop-blur-sm shadow-lg rounded-xl p-4 border border-gray-200">
            <div className="flex items-center gap-2 mb-2">
              <Activity className="w-4 h-4 text-blue-500" />
              <span className="text-sm font-semibold text-gray-900">
                Coverage Status
              </span>
            </div>
            <div className="text-2xl font-bold text-blue-600">
              {cellPolygonsRef.current.size}
            </div>
            <div className="text-xs text-gray-500 mt-1">
              {showCells ? 'Cells visible' : 'Cells hidden'} @ Zoom {zoomLevel}
            </div>
          </div>

          {/* MODIFIED: Legend - Now showing 4 sectors in 2x2 grid */}
          <div className="bg-white/95 backdrop-blur-sm shadow-lg rounded-xl p-4 border border-gray-200">
            <h3 className="text-xs font-semibold text-gray-700 mb-3 flex items-center gap-2">
              <Layers className="w-4 h-4" />
              Sectors
            </h3>
            <div className="grid grid-cols-2 gap-2">
              {["A", "B", "C", "D"].map((sector) => {
                const colors = getColorBySector(sector, 1);
                return (
                  <div key={sector} className="flex items-center gap-2">
                    <div
                      className="w-4 h-4 rounded shadow-sm flex-shrink-0"
                      style={{
                        backgroundColor: colors.fill,
                        border: `2px solid ${colors.stroke}`,
                      }}
                    ></div>
                    <span className="text-xs text-gray-600 font-medium">
                      {sector}
                    </span>
                  </div>
                );
              })}
            </div>
          </div>
        </div>

        {/* Map */}
        {isLoaded ? (
          <GoogleMap
            mapContainerStyle={containerStyle}
            center={center}
            zoom={11}
            onLoad={onLoad}
            onUnmount={onUnmount}
            options={{
              mapId: import.meta.env.VITE_GOOGLE_MAP_ID,
              disableDefaultUI: true,
              zoomControl: true,
              streetViewControl: false,
              mapTypeControl: true,
              fullscreenControl: true,
            }}
          />
        ) : (
          <div className="h-full w-full flex items-center justify-center bg-gray-100">
            <p className="text-gray-500">Loading map...</p>
          </div>
        )}

        {/* Selected Cell Panel */}
        {selectedCell && (
          <div className="absolute bottom-6 left-1/2 transform -translate-x-1/2 z-10 bg-white shadow-2xl rounded-2xl p-5 border border-gray-200 max-w-lg">
            <div className="flex items-start justify-between mb-4">
              <div className="flex-1">
                <div className="flex items-center gap-2 mb-1">
                  <Antenna className="w-5 h-5 text-blue-600" />
                  <h3 className="font-bold text-gray-900 text-lg">
                    {selectedCell.Cell_Name}
                  </h3>
                </div>
                <p className="text-sm text-gray-500 flex items-center gap-2">
                  <Building2 className="w-3 h-3" />
                  Site {selectedCell.SITEID} • Layer {extractCellLayer(selectedCell.Cell_Name)}
                </p>
              </div>
              <button
                onClick={() => setSelectedCell(null)}
                className="p-2 hover:bg-gray-100 rounded-lg transition-colors"
              >
                <X className="w-5 h-5 text-gray-500" />
              </button>
            </div>

            <div className="grid grid-cols-4 gap-3">
              <div className="bg-blue-50 rounded-xl p-3 border border-blue-200">
                <Compass className="w-5 h-5 text-blue-600 mb-2" />
                <div className="text-xs text-blue-600 font-semibold mb-1">
                  Azimuth
                </div>
                <div className="text-xl font-black text-blue-900">
                  {selectedCell.AZIMUTH}°
                </div>
              </div>
              <div className="bg-violet-50 rounded-xl p-3 border border-violet-200">
                <Hash className="w-5 h-5 text-violet-600 mb-2" />
                <div className="text-xs text-violet-600 font-semibold mb-1">
                  PCI
                </div>
                <div className="text-xl font-black text-violet-900">
                  {selectedCell.PCI}
                </div>
              </div>
              <div className="bg-emerald-50 rounded-xl p-3 border border-emerald-200">
                <Waves className="w-5 h-5 text-emerald-600 mb-2" />
                <div className="text-xs text-emerald-600 font-semibold mb-1">
                  Freq
                </div>
                <div className="text-sm font-black text-emerald-900">
                  {selectedCell.Downlink_Center_Frequency}
                </div>
              </div>
              <div className="bg-orange-50 rounded-xl p-3 border border-orange-200">
                <TrendingUp className="w-5 h-5 text-orange-600 mb-2" />
                <div className="text-xs text-orange-600 font-semibold mb-1">
                  Layer
                </div>
                <div className="text-xl font-black text-orange-900">
                  {extractCellLayer(selectedCell.Cell_Name)}
                </div>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}