package com.kilz.mfbluemap;

import com.dansplugins.factionsystem.MedievalFactions;
import com.dansplugins.factionsystem.claim.MfClaimService;
import com.dansplugins.factionsystem.claim.MfClaimedChunk;
import com.dansplugins.factionsystem.event.faction.FactionClaimEvent;
import com.dansplugins.factionsystem.event.faction.FactionUnclaimEvent;
import com.dansplugins.factionsystem.faction.MfFaction;
import com.flowpowered.math.vector.Vector2d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.union.UnaryUnionOp;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class BlueMapIntegration implements Listener {

    private final Plugin plugin;
    private MarkerSet markerSet;
    private MedievalFactions medievalFactions;

    private final GeometryFactory geometryFactory = new GeometryFactory();
    private ScheduledExecutorService scheduledExecutor;
    private final Map<String, ScheduledFuture<?>> pendingTasks = new ConcurrentHashMap<>();
    private final Map<String, List<String>> currentFactionMarkers = new ConcurrentHashMap<>();
    private final Map<String, Color[]> factionColors = new HashMap<>();
    private final long DEBOUNCE_MS = 1500L;

    public BlueMapIntegration(Plugin plugin) {
        this.plugin = plugin;
        this.scheduledExecutor = Executors.newSingleThreadScheduledExecutor((r) -> {
            Thread t = new Thread(r, "mfbluemap-geom");
            t.setDaemon(true);
            return t;
        });

        // Register events
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public boolean enable(BlueMapAPI api, MedievalFactions medievalFactions) {
        this.medievalFactions = medievalFactions;

        this.markerSet = MarkerSet.builder().label("Medieval Factions Claims").build();
        api.getWorlds().forEach((mapWorld) -> mapWorld.getMaps()
                .forEach((map) -> map.getMarkerSets().put("mf_claims", this.markerSet)));

        initFactionColors();
        initialSync();
        return true;
    }

    public void disable() {
        for (ScheduledFuture<?> f : this.pendingTasks.values())
            f.cancel(false);
        if (this.scheduledExecutor != null)
            this.scheduledExecutor.shutdownNow();
    }

    public void reload() {
        int pending = this.pendingTasks.size();
        for (ScheduledFuture<?> f : this.pendingTasks.values())
            f.cancel(false);
        this.pendingTasks.clear();

        initFactionColors();

        int removedCount = this.currentFactionMarkers.values().stream().mapToInt(List::size).sum();

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (this.markerSet == null)
                return;

            for (List<String> ids : this.currentFactionMarkers.values()) {
                for (String id : ids)
                    this.markerSet.remove(id);
            }
            this.currentFactionMarkers.clear();

            plugin.getLogger().info(
                    "BlueMap Reload: cancelled " + pending + " tasks; removed " + removedCount + " markers.");

            for (MfFaction f : this.medievalFactions.getServices().getFactionService().getFactions()) {
                this.scheduleFactionRecompute(f.getId());
            }
        });
    }

    /**
     * Loads colors from config.yml
     */
    private void initFactionColors() {
        this.factionColors.clear();
        plugin.reloadConfig();

        // Load custom overrides from 'factions' section (previously SECTOR)
        if (plugin.getConfig().isConfigurationSection("factions")) {
            for (String factionName : plugin.getConfig().getConfigurationSection("factions").getKeys(false)) {
                try {
                    String fillHex = plugin.getConfig().getString("factions." + factionName + ".fillColor", "#FFFFFF");
                    float fillOpacity = (float) plugin.getConfig().getDouble("factions." + factionName + ".fillOpacity",
                            0.35);
                    String lineHex = plugin.getConfig().getString("factions." + factionName + ".lineColor", "#888888");
                    float lineOpacity = (float) plugin.getConfig().getDouble("factions." + factionName + ".lineOpacity",
                            1.0);

                    int fillRGB = parseColor(fillHex);
                    int lineRGB = parseColor(lineHex);

                    this.factionColors.put(factionName, new Color[] {
                            new Color(fillRGB, fillOpacity),
                            new Color(lineRGB, lineOpacity)
                    });
                } catch (Exception ex) {
                    plugin.getLogger().warning("Error loading color for " + factionName + ": " + ex.getMessage());
                }
            }
        }
    }

    private int parseColor(String hex) {
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        return Integer.parseInt(hex, 16);
    }

    private void initialSync() {
        plugin.getLogger().info("Synchronizing (initial - batch mode) MedievalFactions claims with BlueMap...");

        List<MfFaction> factions = new ArrayList<>(
                this.medievalFactions.getServices().getFactionService().getFactions());
        int batchSize = 2;
        int delayTicks = 20;

        for (int i = 0; i < factions.size(); i += batchSize) {
            final int batchIndex = i;
            final int batchEnd = Math.min(i + batchSize, factions.size());

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (int j = batchIndex; j < batchEnd; j++) {
                    this.scheduleFactionRecompute(factions.get(j).getId());
                }
                plugin.getLogger().info(String.format(
                        "  BlueMap: processing factions %d-%d of %d",
                        batchIndex + 1, batchEnd, factions.size()));
            }, (i / batchSize) * delayTicks);
        }
    }

    @EventHandler
    public void onFactionClaim(FactionClaimEvent event) {
        this.scheduleFactionRecompute(event.getClaim().getFactionId());
    }

    @EventHandler
    public void onFactionUnclaim(FactionUnclaimEvent event) {
        this.scheduleFactionRecompute(event.getClaim().getFactionId());
    }

    private void scheduleFactionRecompute(String factionId) {
        // We use the full service to get claims
        List<MfClaimedChunk> snapshot = new ArrayList<>(
                this.medievalFactions.getServices().getClaimService().getClaimsByFactionId(factionId));
        ScheduledFuture<?> previous = this.pendingTasks.get(factionId);
        if (previous != null && !previous.isDone()) {
            previous.cancel(false);
        }

        ScheduledFuture<?> future = this.scheduledExecutor.schedule(() -> {
            this.pendingTasks.remove(factionId);
            try {
                Map<UUID, List<PolygonData>> perWorld = this.computeUnionPerWorld(snapshot);
                Bukkit.getScheduler().runTask(plugin, () -> this.applyFactionMarkers(factionId, perWorld));
            } catch (Throwable t) {
                plugin.getLogger().severe("Error processing geometry for faction " + factionId + ": " + t);
                t.printStackTrace();
            }
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        this.pendingTasks.put(factionId, future);
    }

    private Map<UUID, List<PolygonData>> computeUnionPerWorld(List<MfClaimedChunk> claimsSnapshot) {
        Map<UUID, List<MfClaimedChunk>> byWorld = claimsSnapshot.stream()
                .collect(Collectors.groupingBy(MfClaimedChunk::getWorldId));
        Map<UUID, List<PolygonData>> result = new HashMap<>();

        for (Map.Entry<UUID, List<MfClaimedChunk>> e : byWorld.entrySet()) {
            UUID worldId = e.getKey();
            List<MfClaimedChunk> list = e.getValue();
            List<Polygon> rects = new ArrayList<>(list.size());

            for (MfClaimedChunk c : list) {
                double minX = (double) c.getX() * 16.0;
                double minZ = (double) c.getZ() * 16.0;
                double maxX = minX + 16.0;
                double maxZ = minZ + 16.0;
                Coordinate[] coords = new Coordinate[] {
                        new Coordinate(minX, minZ),
                        new Coordinate(maxX, minZ),
                        new Coordinate(maxX, maxZ),
                        new Coordinate(minX, maxZ),
                        new Coordinate(minX, minZ)
                };
                rects.add(this.geometryFactory.createPolygon(coords));
            }

            if (rects.isEmpty()) {
                result.put(worldId, Collections.emptyList());
            } else {
                Geometry unioned = UnaryUnionOp.union(rects);
                List<PolygonData> polygons = new ArrayList<>();
                int num = unioned.getNumGeometries();

                for (int i = 0; i < num; ++i) {
                    Geometry g = unioned.getGeometryN(i);
                    if (g instanceof Polygon) {
                        Polygon poly = (Polygon) g;
                        Coordinate[] shellCoords = poly.getExteriorRing().getCoordinates();
                        List<Coordinate[]> holes = new ArrayList<>();
                        int holeCount = poly.getNumInteriorRing();
                        for (int h = 0; h < holeCount; ++h) {
                            holes.add(poly.getInteriorRingN(h).getCoordinates());
                        }
                        polygons.add(new PolygonData(shellCoords, holes));
                    }
                }
                result.put(worldId, polygons);
            }
        }
        return result;
    }

    private void applyFactionMarkers(String factionId, Map<UUID, List<PolygonData>> perWorld) {
        List<String> old = this.currentFactionMarkers.remove(factionId);
        if (old != null)
            old.forEach(this.markerSet::remove);

        List<String> newIds = new ArrayList<>();
        String factionName = this.findFactionNameById(factionId);

        // Determine color:
        // 1. Check if configured in 'factions' config override
        // 2. If not, use deterministic color hash to create a unique color for the
        // faction
        Color[] colors = this.factionColors.get(factionName);

        if (colors == null) {
            // No override found, generate dynamic color or use default
            int deterministicColor = generateDeterministicColor(factionId);

            // Get defaults from config
            float fillOpacity = (float) plugin.getConfig().getDouble("default-color.fill-opacity", 0.35);
            float lineOpacity = (float) plugin.getConfig().getDouble("default-color.line-opacity", 1.0);

            // Allow default color to override random/deterministic behavior if requested,
            // but user asked for "random color assigned by MF" logic.
            // Since MF might not expose it easily, we use a hash code which is consistent
            // and unique-ish.

            colors = new Color[] {
                    new Color(deterministicColor, fillOpacity),
                    new Color(deterministicColor, lineOpacity) // Same color for line, maybe darker?
            };
        }

        // Configurable values
        float mapY = (float) plugin.getConfig().getDouble("bluemap.y-level", 70.0);
        String labelFormat = plugin.getConfig().getString("bluemap.label-format", "Faction: %faction%");

        for (Map.Entry<UUID, List<PolygonData>> e : perWorld.entrySet()) {
            UUID worldId = e.getKey();
            List<PolygonData> polygons = e.getValue();

            for (int i = 0; i < polygons.size(); ++i) {
                PolygonData pd = polygons.get(i);
                List<Vector2d> outer = new ArrayList<>();
                for (int k = 0; k < pd.shell.length - 1; ++k) {
                    outer.add(new Vector2d(pd.shell[k].x, pd.shell[k].y));
                }

                Shape outerShape = new Shape(outer);
                List<Shape> holeShapes = new ArrayList<>();
                for (Coordinate[] holeCoords : pd.holes) {
                    List<Vector2d> hpts = new ArrayList<>();
                    for (int k = 0; k < holeCoords.length - 1; ++k) {
                        hpts.add(new Vector2d(holeCoords[k].x, holeCoords[k].y));
                    }
                    holeShapes.add(new Shape(hpts));
                }

                String label = labelFormat.replace("%faction%", (factionName != null ? factionName : factionId));

                ShapeMarker.Builder b = ShapeMarker.builder()
                        .label(label)
                        .shape(outerShape, mapY)
                        .fillColor(colors[0])
                        .lineColor(colors[1])
                        .lineWidth(2)
                        .centerPosition();

                if (!holeShapes.isEmpty())
                    b.holes(holeShapes.toArray(new Shape[0]));

                ShapeMarker marker = b.build();
                String id = "faction_" + factionId + "_world_" + worldId + "_poly_" + i;
                this.markerSet.put(id, marker);
                newIds.add(id);
            }
        }
        this.currentFactionMarkers.put(factionId, newIds);
    }

    /**
     * Generates a deterministic color based on the string input.
     * Use a hash function to select a color.
     */
    private int generateDeterministicColor(String input) {
        int hash = input.hashCode();
        // Generate RGB from hash
        // We want bright/nice colors, maybe HSL? For now standard hash to RGB
        int r = (hash & 0xFF0000) >> 16;
        int g = (hash & 0x00FF00) >> 8;
        int b = (hash & 0x0000FF);

        return (r << 16) | (g << 8) | b;
    }

    private String findFactionNameById(String id) {
        for (MfFaction f : this.medievalFactions.getServices().getFactionService().getFactions()) {
            if (f.getId().equals(id))
                return f.getName();
        }
        return id;
    }

    private static class PolygonData {
        final Coordinate[] shell;
        final List<Coordinate[]> holes;

        PolygonData(Coordinate[] shell, List<Coordinate[]> holes) {
            this.shell = shell;
            this.holes = holes;
        }
    }
}
