package rs117.hd.renderer.zone;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import rs117.hd.renderer.zone.RecreatedSceneRenderer.FloorTile;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.tile_overrides.TileOverride;
import rs117.hd.scene.water_types.WaterType;
import rs117.hd.utils.HDUtils;

import static net.runelite.api.Perspective.*;
import static rs117.hd.utils.MathUtils.*;

/**
 * Developer tools: blends a recreated floor's tiles into each other, the way 117 HD's ProceduralGenerator blends the real
 * ground. Corners shared by neighbouring tiles get one smoothed normal, colour and ground material, picked from all the
 * tiles meeting there, and corners where overlays meet underlays are marked for the shader to blend between them.
 * <p>
 * Corners are matched by where they are in the recreated floor, including their height, so tiles on different levels
 * don't blend into each other.
 */
class RecreatedFloorBlending {
	private static final int OVERRIDE_MAIN = 0;
	private static final int OVERRIDE_OVERLAY = 2;
	private static final short[] NO_NORMAL = new short[3];

	// Each tile's main, underlay & overlay tile overrides, and the tiles whose main override was found by their overlay
	private final Map<FloorTile, TileOverride[]> overrides = new IdentityHashMap<>();
	private final Set<FloorTile> overlayMainTiles;
	private final Map<Long, float[]> normals = new HashMap<>();
	private final Map<Long, Integer> colors = new HashMap<>();
	private final Map<Long, Material> materials = new HashMap<>();
	private final Set<Long> overlayCorners = new HashSet<>();
	private final Set<Long> underlayCorners = new HashSet<>();
	private final Set<Long> highPriorityColors = new HashSet<>();

	RecreatedFloorBlending(List<FloorTile> floor, Map<FloorTile, TileOverride[]> overrides, Set<FloorTile> overlayMainTiles) {
		this.overrides.putAll(overrides);
		this.overlayMainTiles = overlayMainTiles;
		for (var tile : floor)
			addNormals(tile);
		for (var tile : floor)
			addTerrainData(tile);
	}

	/**
	 * The tile's main, underlay & overlay tile overrides, or none if the capture didn't save where it was
	 */
	@Nullable
	TileOverride[] getOverrides(FloorTile tile) {
		return overrides.get(tile);
	}

	/**
	 * Like ProceduralGenerator.useDefaultColor: whether the tile keeps its own colour instead of blending
	 */
	static boolean useDefaultColor(FloorTile tile, int triangle, TileOverride override) {
		if (isPainted(tile) ? tile.textures != null && tile.textures[triangle] >= 0 : hasTextures(tile))
			return true;
		if (override == TileOverride.NONE)
			return false;
		return !override.blended;
	}

	/**
	 * Whether a painted tile's main override was found by its overlay, which it blends as
	 */
	boolean isMainOverlay(FloorTile tile) {
		return overlayMainTiles.contains(tile);
	}

	int getColor(long corner, int color) {
		return colors.getOrDefault(corner, color);
	}

	Material getMaterial(long corner, Material material) {
		return materials.getOrDefault(corner, material);
	}

	/**
	 * Whether an overlay and an underlay meet at the corner, for the shader to blend between them
	 */
	boolean isOverlayEdge(long corner) {
		return overlayCorners.contains(corner) && underlayCorners.contains(corner);
	}

	/**
	 * The corner's smoothed normal, or the given one if it has none
	 */
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

	/**
	 * Where a corner of a tile's triangle is in the recreated floor, with its height
	 */
	static long cornerKey(FloorTile tile, int vertex) {
		long x = tile.tileExX * LOCAL_TILE_SIZE + tile.vertices[vertex * 3];
		long y = tile.vertices[vertex * 3 + 1];
		long z = tile.tileExY * LOCAL_TILE_SIZE + tile.vertices[vertex * 3 + 2];
		return (x & 0xFFFFF) << 40 | (z & 0xFFFFF) << 20 | y & 0xFFFFF;
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

	/**
	 * Like ProceduralGenerator's TerrainNormalGenerator: add each triangle's surface normal to its corners
	 */
	private void addNormals(FloorTile tile) {
		int[] a = new int[3], b = new int[3], c = new int[3], normal = new int[3];
		int[] v = tile.vertices;
		for (int corner = 0; corner < tile.colors.length; corner += 3) {
			System.arraycopy(v, corner * 3, a, 0, 3);
			System.arraycopy(v, corner * 3 + 3, b, 0, 3);
			System.arraycopy(v, corner * 3 + 6, c, 0, 3);
			HDUtils.calculateSurfaceNormals(normal, a, b, c);
			for (int k = 0; k < 3; k++) {
				float[] sum = normals.computeIfAbsent(cornerKey(tile, corner + k), key -> new float[3]);
				sum[0] += normal[0];
				sum[1] += normal[1];
				sum[2] += normal[2];
			}
		}
	}

	/**
	 * Like ProceduralGenerator's TerrainDataGenerator: give each corner a colour & ground material from the tiles meeting
	 * there, preferring overlays, and approximately undoing vanilla's tile lighting
	 */
	private void addTerrainData(FloorTile tile) {
		TileOverride[] tileOverrides = overrides.get(tile);
		int[] worldPos = tile.worldPos;
		if (tileOverrides == null || worldPos == null)
			return;

		boolean painted = isPainted(tile);
		short[] normal = new short[3];
		for (int corner = 0; corner < tile.colors.length; corner++) {
			int triangle = corner / 3;
			int type = tile.overrideTypes == null ? OVERRIDE_MAIN : tile.overrideTypes[triangle];
			var override = tileOverrides[type];
			// Skip water, which isn't recreated
			if (override.waterType != WaterType.NONE)
				continue;

			boolean isOverlay = painted ? isMainOverlay(tile) : type == OVERRIDE_OVERLAY;
			boolean defaultColor = painted ?
				useDefaultColor(tile, triangle, override) :
				isOverlay && useDefaultColor(tile, triangle, override);

			int color = tile.colors[corner];
			if (color < 0 || color == HDUtils.HIDDEN_HSL && !override.forced)
				continue;

			// Near-solid-black tiles used in some places under wall objects only colour corners nothing else colours
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
