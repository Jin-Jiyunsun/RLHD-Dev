package rs117.hd.renderer.zone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import javax.annotation.Nullable;
import rs117.hd.renderer.zone.RecreatedSceneRenderer.FloorTile;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.tile_overrides.TileOverride;
import rs117.hd.scene.water_types.WaterType;
import rs117.hd.utils.HDUtils;

import static net.runelite.api.Perspective.*;
import static rs117.hd.utils.MathUtils.*;

/**
 * Blends a recreated floor like ProceduralGenerator blends the real ground: shared corners get one smoothed normal,
 * colour & material, overlay edges are marked, and water is sunk with foam at the shore. Corners are matched by
 * position & height, so different levels don't blend.
 */
class RecreatedFloorBlending {
	private static final int OVERRIDE_MAIN = 0;
	private static final int OVERRIDE_OVERLAY = 2;
	private static final short[] NO_NORMAL = new short[3];

	// Main, underlay & overlay overrides, and the tiles whose main one was found by their overlay
	private final Map<FloorTile, TileOverride[]> overrides = new IdentityHashMap<>();
	private final Set<FloorTile> overlayMainTiles;
	private final Map<Long, float[]> normals = new HashMap<>();
	private final Map<Long, Integer> colors = new HashMap<>();
	private final Map<Long, Material> materials = new HashMap<>();
	private final Set<Long> overlayCorners = new HashSet<>();
	private final Set<Long> underlayCorners = new HashSet<>();
	private final Set<Long> highPriorityColors = new HashSet<>();
	// Like ProceduralGenerator.seasonalWaterType
	private final BiFunction<TileOverride, Integer, WaterType> waterTypes;
	private final Set<Long> waterCorners = new HashSet<>();
	private final Set<Long> landCorners = new HashSet<>();
	private final Map<Long, Integer> underwaterDepths = new HashMap<>();

	RecreatedFloorBlending(
		List<FloorTile> floor,
		Map<FloorTile, TileOverride[]> overrides,
		Set<FloorTile> overlayMainTiles,
		BiFunction<TileOverride, Integer, WaterType> waterTypes
	) {
		this.overrides.putAll(overrides);
		this.overlayMainTiles = overlayMainTiles;
		this.waterTypes = waterTypes;
		// Sink the water first, since normals follow the sunken ground
		addWater(floor);
		for (var tile : floor)
			addNormals(tile);
		for (var tile : floor)
			addTerrainData(tile);
	}

	/** The tile's main, underlay & overlay tile overrides, or none if the capture didn't save where it was */
	@Nullable
	TileOverride[] getOverrides(FloorTile tile) {
		return overrides.get(tile);
	}

	/** Like ProceduralGenerator.useDefaultColor: whether the tile keeps its own colour instead of blending */
	static boolean useDefaultColor(FloorTile tile, int triangle, TileOverride override) {
		if (isPainted(tile) ? tile.textures != null && tile.textures[triangle] >= 0 : hasTextures(tile))
			return true;
		if (override == TileOverride.NONE)
			return false;
		return !override.blended;
	}

	/** Whether a painted tile's main override was found by its overlay, which it blends as */
	boolean isMainOverlay(FloorTile tile) {
		return overlayMainTiles.contains(tile);
	}

	/** The water type of a tile's triangle, or none if it isn't water */
	WaterType getWaterType(FloorTile tile, int triangle) {
		TileOverride[] tileOverrides = overrides.get(tile);
		var override = tileOverrides == null ? TileOverride.NONE : tileOverrides[tile.overrideType(triangle)];
		int texture = tile.texture(triangle);
		return waterTypes.apply(override, texture);
	}

	/** How far below the water's surface the ground at the corner is sunk */
	int getUnderwaterDepth(long corner) {
		return underwaterDepths.getOrDefault(corner, 0);
	}

	/** Whether water meets land at the corner, where the water shader draws foam */
	boolean isShore(long corner) {
		return waterCorners.contains(corner) && landCorners.contains(corner);
	}

	int getColor(long corner, int color) {
		return colors.getOrDefault(corner, color);
	}

	Material getMaterial(long corner, Material material) {
		return materials.getOrDefault(corner, material);
	}

	/** Whether an overlay and an underlay meet at the corner, for the shader to blend between them */
	boolean isOverlayEdge(long corner) {
		return overlayCorners.contains(corner) && underlayCorners.contains(corner);
	}

	/** The corner's smoothed normal, or the given one if it has none */
	short[] getNormal(long corner, short[] result, short[] defaultNormal) {
		float[] n = normals.get(corner);
		float length = n == null ? 0 : n[0] * n[0] + n[1] * n[1] + n[2] * n[2];
		if (length == 0) {
			System.arraycopy(defaultNormal, 0, result, 0, 3);
			return result;
		}
		float invLength = rcp(sqrt(length));
		result[0] = normShort(n[0] * invLength);
		result[1] = normShort(n[1] * invLength);
		result[2] = normShort(n[2] * invLength);
		return result;
	}

	/** Where a corner of a tile's triangle is in the recreated floor, with its height */
	static long cornerKey(FloorTile tile, int vertex) {
		return cornerKey(
			tile.tileExX * LOCAL_TILE_SIZE + tile.vertices[vertex * 3],
			tile.vertices[vertex * 3 + 1],
			tile.tileExY * LOCAL_TILE_SIZE + tile.vertices[vertex * 3 + 2]
		);
	}

	private static long cornerKey(long x, long y, long z) {
		return (x & 0xFFFFF) << 40 | (z & 0xFFFFF) << 20 | y & 0xFFFFF;
	}

	private static long cornerX(long key) {
		return key >> 40 & 0xFFFFF;
	}

	private static long cornerZ(long key) {
		return key >> 20 & 0xFFFFF;
	}

	private static long cornerY(long key) {
		// Heights are signed
		return key << 44 >> 44;
	}

	private static boolean isOnTileCorner(FloorTile tile, int vertex) {
		return tile.vertices[vertex * 3] % LOCAL_TILE_SIZE == 0 && tile.vertices[vertex * 3 + 2] % LOCAL_TILE_SIZE == 0;
	}

	/** Like UnderwaterTerrainGenerator: sink water a level per ring from the shore, interpolating shaped tiles */
	private void addWater(List<FloorTile> floor) {
		// Depth levels on the tile grid: 0 is land, 1 the water at the shore
		Map<Long, Integer> levels = new HashMap<>();
		Set<Long> landGrid = new HashSet<>();
		List<FloorTile> waterTiles = new ArrayList<>();
		for (var tile : floor) {
			boolean hasWater = false;
			TileOverride[] tileOverrides = overrides.get(tile);
			for (int corner = 0; corner < tile.colors.length; corner++) {
				int triangle = corner / 3;
				long key = cornerKey(tile, corner);
				if (getWaterType(tile, triangle) == WaterType.NONE) {
					int type = tile.overrideType(triangle);
					boolean forced = tileOverrides != null && tileOverrides[type].forced;
					if (tile.colors[corner] != HDUtils.HIDDEN_HSL || forced)
						landCorners.add(key);
					if (isOnTileCorner(tile, corner))
						landGrid.add(key);
				} else {
					hasWater = true;
					waterCorners.add(key);
					if (isOnTileCorner(tile, corner))
						levels.putIfAbsent(key, 1);
				}
			}
			if (hasWater)
				waterTiles.add(tile);
		}
		if (waterTiles.isEmpty())
			return;
		levels.keySet().removeAll(landGrid);

		List<Long> water = new ArrayList<>(levels.keySet());
		water.sort(Comparator.<Long>comparingLong(RecreatedFloorBlending::cornerX).thenComparingLong(RecreatedFloorBlending::cornerZ));
		for (int pass = 0; pass < ProceduralGenerator.DEPTH_LEVEL_SLOPE.length - 1; pass++) {
			for (long key : water) {
				int level = levels.get(key);
				long x = cornerX(key), y = cornerY(key), z = cornerZ(key);
				if (levels.getOrDefault(cornerKey(x - LOCAL_TILE_SIZE, y, z), 0) < level ||
					levels.getOrDefault(cornerKey(x, y, z - LOCAL_TILE_SIZE), 0) < level ||
					levels.getOrDefault(cornerKey(x + LOCAL_TILE_SIZE, y, z), 0) < level ||
					levels.getOrDefault(cornerKey(x, y, z + LOCAL_TILE_SIZE), 0) < level)
					continue;
				levels.put(key, level + 1);
			}
		}

		for (var tile : waterTiles) {
			// For interpolating corners inside the tile
			int[] cornerDepths = new int[4];
			for (int corner = 0; corner < tile.colors.length; corner++) {
				if (!isOnTileCorner(tile, corner))
					continue;
				int depth = getDepth(levels.getOrDefault(cornerKey(tile, corner), 0));
				int x = tile.vertices[corner * 3] / LOCAL_TILE_SIZE;
				int z = tile.vertices[corner * 3 + 2] / LOCAL_TILE_SIZE;
				cornerDepths[z * 2 + x] = depth;
				if (depth > 0)
					underwaterDepths.put(cornerKey(tile, corner), depth);
			}
			for (int corner = 0; corner < tile.colors.length; corner++) {
				long key = cornerKey(tile, corner);
				if (isOnTileCorner(tile, corner) || landCorners.contains(key))
					continue;
				float lerpX = tile.vertices[corner * 3] / (float) LOCAL_TILE_SIZE;
				float lerpZ = tile.vertices[corner * 3 + 2] / (float) LOCAL_TILE_SIZE;
				float south = mix(cornerDepths[0], cornerDepths[1], lerpX);
				float north = mix(cornerDepths[2], cornerDepths[3], lerpX);
				int depth = (int) mix(south, north, lerpZ);
				if (depth > 0)
					underwaterDepths.put(key, depth);
			}
		}
	}

	/** Like ProceduralGenerator's getHeightOffset */
	private static int getDepth(int level) {
		if (level == 0)
			return 0;
		return (int) (ProceduralGenerator.DEPTH_LEVEL_SLOPE[level - 1] * .55f);
	}

	static boolean isPainted(FloorTile tile) {
		return tile.overrideTypes == null || tile.overrideTypes[0] == OVERRIDE_MAIN;
	}

	private static boolean hasTextures(FloorTile tile) {
		if (tile.textures == null)
			return false;
		for (int texture : tile.textures)
			if (texture != -1)
				return true;
		return false;
	}

	/** Like TerrainNormalGenerator */
	private void addNormals(FloorTile tile) {
		int[] a = new int[3], b = new int[3], c = new int[3], normal = new int[3];
		int[] v = tile.vertices;
		for (int corner = 0; corner < tile.colors.length; corner += 3) {
			System.arraycopy(v, corner * 3, a, 0, 3);
			System.arraycopy(v, corner * 3 + 3, b, 0, 3);
			System.arraycopy(v, corner * 3 + 6, c, 0, 3);
			a[1] += getUnderwaterDepth(cornerKey(tile, corner));
			b[1] += getUnderwaterDepth(cornerKey(tile, corner + 1));
			c[1] += getUnderwaterDepth(cornerKey(tile, corner + 2));
			HDUtils.calculateSurfaceNormals(normal, a, b, c);
			for (int k = 0; k < 3; k++) {
				float[] sum = normals.computeIfAbsent(cornerKey(tile, corner + k), key -> new float[3]);
				sum[0] += normal[0];
				sum[1] += normal[1];
				sum[2] += normal[2];
			}
		}
	}

	/** Like TerrainDataGenerator: corner colours & materials from the tiles meeting there, preferring overlays */
	private void addTerrainData(FloorTile tile) {
		TileOverride[] tileOverrides = overrides.get(tile);
		int[] worldPos = tile.worldPos;
		if (tileOverrides == null || worldPos == null)
			return;

		boolean painted = isPainted(tile);
		short[] normal = new short[3];
		for (int corner = 0; corner < tile.colors.length; corner++) {
			int triangle = corner / 3;
			int type = tile.overrideType(triangle);
			var override = tileOverrides[type];
			// Water's ground has its own colour & material
			if (override.waterType != WaterType.NONE)
				continue;

			boolean isOverlay = painted ? isMainOverlay(tile) : type == OVERRIDE_OVERLAY;
			boolean defaultColor = painted ?
				useDefaultColor(tile, triangle, override) :
				isOverlay && useDefaultColor(tile, triangle, override);

			int color = tile.colors[corner];
			if (color < 0 || color == HDUtils.HIDDEN_HSL && !override.forced)
				continue;

			// Near-black tiles under some walls only colour corners nothing else does
			boolean lowPriorityColor = color <= 2;

			long key = cornerKey(tile, corner);
			float dot = 0;
			getNormal(key, normal, NO_NORMAL);
			float length = (float) normal[0] * normal[0] + (float) normal[1] * normal[1] + (float) normal[2] * normal[2];
			if (length >= EPSILON)
				dot = (normal[0] + normal[2]) / sqrt(2 * length);

			int lightness = color & 0x7F;
			float lightFactor = Math.max(dot, 0f);
			int adjustedLight = (int) (Math.max(lightness - 3, 0) * 1.5f + 15);
			lightness = (int) mix(lightness, adjustedLight, lightFactor);
			float darkFactor = -Math.min(dot, 0f);
			int darkened = (int) (lightness * 0.5f);
			lightness = (int) (1.25f * mix(lightness, darkened, darkFactor));
			lightness = Math.min(lightness, 55);
			color = override.modifyColor(color & ~0x7F | lightness);

			Material material = override.groundMaterial.getRandomMaterial(worldPos);
			boolean blendAsOverlay = isOverlay != override.blendedAsOpposite;
			(blendAsOverlay ? overlayCorners : underlayCorners).add(key);

			if ((!lowPriorityColor || !highPriorityColors.contains(key)) && !defaultColor) {
				if (blendAsOverlay || !colors.containsKey(key)) {
					colors.put(key, color);
					materials.put(key, material);
				} else {
					colors.putIfAbsent(key, color);
					materials.putIfAbsent(key, material);
				}
				if (!lowPriorityColor)
					highPriorityColors.add(key);
			}
		}
	}
}
