package rs117.hd.dev.spawner;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import rs117.hd.HdPlugin;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.ResourcePath;

/**
 * Developer tools: saves the objects, NPCs and ground the game draws around the player, so {@link SceneTools} can
 * recreate them somewhere else. Captures are kept as JSON files in 117 HD's folder in .runelite.
 */
public class SceneCapture {
	public static final ResourcePath CAPTURES_PATH = HdPlugin.PLUGIN_DIR.resolve("scene_captures");

	// Tiles in each direction which make up an instance's region-sized area around the player
	private static final int INSTANCE_REGION_RADIUS = 32;
	// Which of a tile's tile overrides a floor triangle uses: the main one for painted tiles, or underlay or overlay
	public static final int OVERRIDE_MAIN = 0;
	public static final int OVERRIDE_UNDERLAY = 1;
	public static final int OVERRIDE_OVERLAY = 2;
	// Tiles of ground saved around the captured objects, enough for big models
	private static final int TERRAIN_MARGIN = 8;
	private static final int SHAPE_MASK = 0x3F;
	private static final int SHAPE_WALL_CORNER = 2;
	private static final int SHAPE_DECORATION_STRAIGHT = 4;
	private static final int SHAPE_DECORATION_DIAGONAL_OFFSET = 6;
	private static final int SHAPE_DECORATION_DIAGONAL_NO_OFFSET = 7;
	private static final int SHAPE_DECORATION_DIAGONAL_BOTH = 8;
	private static final int SHAPE_CENTREPIECE = 10;
	private static final int SHAPE_CENTREPIECE_DIAGONAL = 11;
	private static final int SHAPE_ROOF_FIRST = 12;
	private static final int SHAPE_ROOF_LAST = 21;
	private static final int SHAPE_GROUND_DECORATION = 22;

	/**
	 * How much of the scene to capture
	 */
	@RequiredArgsConstructor
	public enum Area {
		REGION("This region", -1),
		NEARBY("Within 16 tiles", 16),
		SCENE("Whole scene", -1);

		private final String label;
		// Tiles in each direction around the player, or -1 for the region or whole scene
		private final int tileRadius;

		@Override
		public String toString() {
			return label;
		}
	}

	public enum Kind {
		GAME,
		WALL,
		DECORATION,
		GROUND
	}

	public static class Capture {
		public String name;
		// Where the player stood, whose plane turns the relative planes below back into the terrain's
		public WorldPoint capturedAt;
		// The environments.json environment in use, by key or area name, which recreating the capture switches to
		@Nullable
		public String environment;
		public List<CapturedObject> objects = new ArrayList<>();
		// The ground's tiles, recreated as a floor in their shapes & colours
		public List<CapturedTile> tiles = new ArrayList<>();
		// NPCs, recreated as frozen copies of their models, with no animations or actions
		public List<CapturedNpc> npcs = new ArrayList<>();
		// The ground under the objects & tiles, for the floor's shape and objects which bend to follow the ground
		@Nullable
		public Terrain terrain;
	}

	/**
	 * One NPC, positioned like objects, facing the way it faced
	 */
	public static class CapturedNpc {
		public int id;
		public int x;
		public int y;
		public int height;
		public int plane;
		public int orientation;
	}

	/**
	 * One tile of ground, positioned in tiles relative to the corner of the player's region, and in planes relative to
	 * the player
	 */
	public static class CapturedTile {
		public int x;
		public int y;
		public int plane;
		// Three corners per triangle, each as a position within the tile & a height relative to the ground the player
		// stood on, with a colour for each corner
		public int[] vertices;
		public int[] colors;
		// Where the tile really is, in the real map's coordinates for instances, and its underlay & overlay, which pick its
		// tile_overrides.json override again whenever it's uploaded, so edits to the file show up without recapturing
		@Nullable
		public int[] worldPos;
		public int underlayId;
		public int overlayId;
		// For each triangle: its vanilla texture or -1, and which of the tile's overrides it uses
		public int[] textures;
		public int[] overrideTypes;
	}

	/**
	 * The height of each tile corner in the captured area, so objects which follow the ground can be bent the same way
	 * wherever they're recreated
	 */
	public static class Terrain {
		// The south-west tile, relative to the corner of the player's region, and how many tiles across
		public int x;
		public int y;
		public int width;
		public int height;
		// For each of the scene's planes, the heights of the tile corners row by row from the south, relative to the
		// ground the player stood on, where more negative is higher up
		public int[][] heights;

		/**
		 * The ground's height at a point in local units relative to the region's corner, blended between tile corners
		 * the same way the client does, or null if the point isn't covered
		 */
		@Nullable
		Integer getHeight(int plane, int localX, int localY) {
			if (heights == null || plane < 0 || plane >= heights.length)
				return null;
			int tileX = (localX >> Perspective.LOCAL_COORD_BITS) - x;
			int tileY = (localY >> Perspective.LOCAL_COORD_BITS) - y;
			if (tileX < 0 || tileY < 0 || tileX >= width || tileY >= height)
				return null;
			int fracX = localX & (Perspective.LOCAL_TILE_SIZE - 1);
			int fracY = localY & (Perspective.LOCAL_TILE_SIZE - 1);
			int[] h = heights[plane];
			int row = width + 1;
			int south = h[tileY * row + tileX] * (128 - fracX) + h[tileY * row + tileX + 1] * fracX >> 7;
			int north = h[(tileY + 1) * row + tileX] * (128 - fracX) + h[(tileY + 1) * row + tileX + 1] * fracX >> 7;
			return south * (128 - fracY) + north * fracY >> 7;
		}
	}

	/**
	 * One object, positioned in local units relative to the corner of the player's region, in planes relative to the
	 * player, and in height relative to the ground the player stands on, where more negative is higher up
	 */
	public static class CapturedObject {
		public int id;
		public Kind kind;
		public int shape;
		public int rotation;
		public int x;
		public int y;
		public int height;
		public int plane;
		// Wall decorations' offset from their wall, which only their first part moves by
		public int xOffset;
		public int yOffset;
		// Game objects' extra rotation, such as 45 degrees for diagonal objects
		public int modelOrientation;
		// The model the client drew, kept for objects it builds from more than their models in the cache, like roofs
		@Nullable
		public CapturedModel model;
	}

	/**
	 * A model as the client drew it in the scene, already rotated and bent to the ground, with everything 117 HD reads
	 * from a model when uploading static scenery
	 */
	public static class CapturedModel {
		public float[] verticesX, verticesY, verticesZ;
		public int[] faceIndices1, faceIndices2, faceIndices3;
		public int[] faceColors1, faceColors2, faceColors3;
		@Nullable
		public short[] unlitFaceColors;
		@Nullable
		public byte[] faceTransparencies;
		public byte transparency;
		@Nullable
		public short[] faceTextures;
		@Nullable
		public byte[] textureFaces;
		@Nullable
		public int[] texIndices1, texIndices2, texIndices3;
		@Nullable
		public int[] vertexNormalsX, vertexNormalsY, vertexNormalsZ;
		@Nullable
		public byte[] faceBias;
		@Nullable
		public byte[] faceRenderPriorities;
		public int radius, diameter, modelHeight;

		static CapturedModel of(Model model) {
			int vertices = model.getVerticesCount();
			int faces = model.getFaceCount();
			var captured = new CapturedModel();
			captured.verticesX = Arrays.copyOf(model.getVerticesX(), vertices);
			captured.verticesY = Arrays.copyOf(model.getVerticesY(), vertices);
			captured.verticesZ = Arrays.copyOf(model.getVerticesZ(), vertices);
			captured.faceIndices1 = Arrays.copyOf(model.getFaceIndices1(), faces);
			captured.faceIndices2 = Arrays.copyOf(model.getFaceIndices2(), faces);
			captured.faceIndices3 = Arrays.copyOf(model.getFaceIndices3(), faces);
			captured.faceColors1 = Arrays.copyOf(model.getFaceColors1(), faces);
			captured.faceColors2 = Arrays.copyOf(model.getFaceColors2(), faces);
			captured.faceColors3 = Arrays.copyOf(model.getFaceColors3(), faces);
			captured.unlitFaceColors = copy(model.getUnlitFaceColors(), faces);
			captured.faceTransparencies = copy(model.getFaceTransparencies(), faces);
			captured.transparency = model.getTransparency();
			captured.faceTextures = copy(model.getFaceTextures(), faces);
			captured.textureFaces = copy(model.getTextureFaces(), faces);
			captured.texIndices1 = copy(model.getTexIndices1());
			captured.texIndices2 = copy(model.getTexIndices2());
			captured.texIndices3 = copy(model.getTexIndices3());
			captured.vertexNormalsX = copy(model.getVertexNormalsX(), vertices);
			captured.vertexNormalsY = copy(model.getVertexNormalsY(), vertices);
			captured.vertexNormalsZ = copy(model.getVertexNormalsZ(), vertices);
			captured.faceBias = copy(model.getFaceBias(), faces);
			captured.faceRenderPriorities = copy(model.getFaceRenderPriorities(), faces);
			captured.radius = model.getRadius();
			captured.diameter = model.getDiameter();
			captured.modelHeight = model.getModelHeight();
			return captured;
		}

		/**
		 * A stand-in model with the captured data, for 117 HD to upload. Anything else it's asked for is left empty.
		 */
		public Model toModel() {
			return (Model) Proxy.newProxyInstance(Model.class.getClassLoader(), new Class<?>[] { Model.class }, (proxy, method, args) -> {
				switch (method.getName()) {
					case "getVerticesCount":
						return verticesX.length;
					case "getFaceCount":
						return faceIndices1.length;
					case "getVerticesX":
						return verticesX;
					case "getVerticesY":
						return verticesY;
					case "getVerticesZ":
						return verticesZ;
					case "getFaceIndices1":
						return faceIndices1;
					case "getFaceIndices2":
						return faceIndices2;
					case "getFaceIndices3":
						return faceIndices3;
					case "getFaceColors1":
						return faceColors1;
					case "getFaceColors2":
						return faceColors2;
					case "getFaceColors3":
						return faceColors3;
					case "getUnlitFaceColors":
						return unlitFaceColors;
					case "getFaceTransparencies":
						return faceTransparencies;
					case "getTransparency":
						return transparency;
					case "getFaceTextures":
						return faceTextures;
					case "getTextureFaces":
						return textureFaces;
					case "getTexIndices1":
						return texIndices1;
					case "getTexIndices2":
						return texIndices2;
					case "getTexIndices3":
						return texIndices3;
					case "getVertexNormalsX":
						return vertexNormalsX;
					case "getVertexNormalsY":
						return vertexNormalsY;
					case "getVertexNormalsZ":
						return vertexNormalsZ;
					case "getFaceBias":
						return faceBias;
					case "getFaceRenderPriorities":
						return faceRenderPriorities;
					case "getRadius":
						return radius;
					case "getDiameter":
						return diameter;
					case "getModelHeight":
						return modelHeight;
					case "getModel":
						return proxy;
					case "equals":
						return proxy == args[0];
					case "hashCode":
						return System.identityHashCode(proxy);
					case "toString":
						return "CapturedModel";
				}
				return defaultValue(method.getReturnType());
			});
		}

		@Nullable
		private static Object defaultValue(Class<?> type) {
			if (!type.isPrimitive() || type == void.class)
				return null;
			if (type == boolean.class)
				return false;
			if (type == char.class)
				return (char) 0;
			if (type == byte.class)
				return (byte) 0;
			if (type == short.class)
				return (short) 0;
			if (type == long.class)
				return 0L;
			if (type == float.class)
				return 0f;
			if (type == double.class)
				return 0d;
			return 0;
		}

		@Nullable
		private static int[] copy(@Nullable int[] array, int length) {
			return array == null ? null : Arrays.copyOf(array, length);
		}

		@Nullable
		private static int[] copy(@Nullable int[] array) {
			return array == null ? null : array.clone();
		}

		@Nullable
		private static byte[] copy(@Nullable byte[] array, int length) {
			return array == null ? null : Arrays.copyOf(array, length);
		}

		@Nullable
		private static byte[] copy(@Nullable byte[] array) {
			return array == null ? null : array.clone();
		}

		@Nullable
		private static short[] copy(@Nullable short[] array, int length) {
			return array == null ? null : Arrays.copyOf(array, length);
		}
	}

	/**
	 * One model to spawn for a captured object, chosen the same way the client builds the object for its scene.
	 * Rotations above 3 pick the mirrored or 45 degree variant of a wall or wall decoration model.
	 */
	@RequiredArgsConstructor
	public static class Part {
		public final int modelType;
		public final int rotation;
		public final boolean offset;

		public boolean isMirrored() {
			return modelType == SHAPE_WALL_CORNER && rotation > 3;
		}

		public boolean isDiagonal() {
			return modelType == SHAPE_DECORATION_STRAIGHT && rotation > 3;
		}
	}

	/**
	 * What the game and 117 HD aren't drawing, which captures leave out
	 */
	public interface Visibility {
		// Whether objects at a level under a roof were drawn last frame, or null if nothing has been drawn yet
		@Nullable
		Boolean isLevelDrawn(int level, int roofId);

		// Whether 117 HD's area hiding hides the tile, while the player is in an area which hides everything outside it
		boolean isHiddenByArea(Tile tile);

		// Whether a model override hides the object, given its ModelHash type
		boolean isHiddenByOverride(TileObject object, int type);

		// Where a tile really is, in the real map's coordinates for instances, the same way 117 HD picks its tile overrides
		void getWorldPosition(Tile tile, int level, int[] result);
	}

	/**
	 * Capture every object within the area which the game and 117 HD currently draw, including the extended area 117 HD
	 * loads around the scene. Must be called on the client thread.
	 */
	@Nullable
	public static Capture capture(Client client, String name, Area area, Visibility visibility) {
		assert client.isClientThread();
		Player player = client.getLocalPlayer();
		WorldView worldView = client.getTopLevelWorldView();
		if (player == null || worldView == null)
			return null;
		LocalPoint origin = player.getLocalLocation();
		int originPlane = worldView.getPlane();
		int groundHeight = Perspective.getTileHeight(client, origin, originPlane);

		Capture capture = new Capture();
		capture.name = name;
		capture.capturedAt = player.getWorldLocation();
		Scene scene = worldView.getScene();
		Tile[][][] tiles = scene.getExtendedTiles();
		int cornerX = getRegionCorner(scene.getBaseX(), origin.getX());
		int cornerY = getRegionCorner(scene.getBaseY(), origin.getY());
		// Instances piece rooms together from chunks of several regions, and their own coordinates don't line up with rooms,
		// so capture a region-sized square centred on the player instead
		boolean instanceRegion = area == Area.REGION && worldView.isInstance();
		int tileRadius = instanceRegion ? INSTANCE_REGION_RADIUS : area.tileRadius;
		int originRegion = getRegionId(scene, origin.getSceneX(), origin.getSceneY());
		byte[][][] settings = scene.getExtendedTileSettings();
		int[][][] roofs = scene.getRoofs();
		int extendedOffset = HDUtils.EXTENDED_SCENE_OFFSET;
		var capturer = new Capturer(client, scene, capture, visibility, cornerX, cornerY, originPlane, groundHeight);
		// Tiles which passed the checks above, so NPCs standing on them are captured by the same rules
		Set<Integer> capturedTiles = new HashSet<>();
		for (int z = 0; z < tiles.length; z++) {
			for (Tile[] column : tiles[z]) {
				for (Tile tile : column) {
					if (tile == null)
						continue;
					var sceneLocation = tile.getSceneLocation();
					// Skip what the game hides, like the planes above the player, which instances can fill with other
					// states of the area. Recreated objects aren't hidden like that, so they'd stack on top of each other.
					int roofId = getRoofId(settings, roofs, z, sceneLocation.getX() + extendedOffset, sceneLocation.getY() + extendedOffset);
					Boolean drawn = visibility.isLevelDrawn(z, roofId);
					if (drawn == null ? z > originPlane : !drawn)
						continue;
					if (visibility.isHiddenByArea(tile))
						continue;
					if (area == Area.REGION && !instanceRegion ?
						getRegionId(scene, sceneLocation.getX(), sceneLocation.getY()) != originRegion :
						tileRadius != -1 && (
							Math.abs(sceneLocation.getX() - origin.getSceneX()) > tileRadius ||
							Math.abs(sceneLocation.getY() - origin.getSceneY()) > tileRadius
						))
						continue;

					capturer.captureTile(tile);
					capturer.captureGround(tile);
					capturedTiles.add(packTile(z, sceneLocation.getX(), sceneLocation.getY()));
					// The ground under a bridge is kept as a separate tile, which is drawn along with the bridge
					if (tile.getBridge() != null) {
						capturer.captureTile(tile.getBridge());
						capturer.captureGround(tile.getBridge());
					}
				}
			}
		}
		captureNpcs(client, worldView, capture, capturedTiles, cornerX, cornerY, originPlane, groundHeight);
		capture.terrain = captureTerrain(scene, capture, cornerX, cornerY, groundHeight);
		return capture;
	}

	private static int packTile(int level, int sceneX, int sceneY) {
		return level << 16 | (sceneX + HDUtils.EXTENDED_SCENE_OFFSET) << 8 | sceneY + HDUtils.EXTENDED_SCENE_OFFSET;
	}

	/**
	 * Capture the NPCs standing on captured tiles as they currently look, leaving out pets, which follow players around
	 */
	private static void captureNpcs(
		Client client,
		WorldView worldView,
		Capture capture,
		Set<Integer> capturedTiles,
		int cornerX,
		int cornerY,
		int originPlane,
		int groundHeight
	) {
		for (NPC npc : worldView.npcs()) {
			var composition = npc.getTransformedComposition();
			if (composition == null || composition.isFollower() || composition.getModels() == null)
				continue;
			var location = npc.getLocalLocation();
			int plane = npc.getWorldLocation().getPlane();
			if (!capturedTiles.contains(packTile(plane, location.getSceneX(), location.getSceneY())))
				continue;

			var captured = new CapturedNpc();
			captured.id = composition.getId();
			captured.x = location.getX() - cornerX;
			captured.y = location.getY() - cornerY;
			captured.height = Perspective.getTileHeight(client, location, plane) - groundHeight;
			captured.plane = plane - originPlane;
			captured.orientation = npc.getOrientation();
			capture.npcs.add(captured);
		}
	}

	/**
	 * Captures the objects on tiles, relative to the corner of the player's region
	 */
	@RequiredArgsConstructor
	private static class Capturer {
		final Client client;
		final Scene scene;
		final Capture capture;
		final Visibility visibility;
		final int cornerX;
		final int cornerY;
		final int originPlane;
		final int groundHeight;

		void captureTile(Tile tile) {
			for (GameObject object : tile.getGameObjects()) {
				// Skip actors & projectiles, and objects bigger than one tile everywhere but their first tile
				if (object == null || ModelHash.isTemporaryObject(object.getHash()) ||
					object.getRenderable() == null || object.getRenderable() instanceof Actor ||
					!object.getSceneMinLocation().equals(tile.getSceneLocation()))
					continue;
				var captured = capture(tile, object, Kind.GAME, object.getConfig(), ModelHash.TYPE_GAME_OBJECT);
				if (captured != null) {
					captured.modelOrientation = object.getModelOrientation();
					// The client builds some roofs from more than their models in the cache, so keep the model it draws
					var renderable = object.getRenderable();
					if (captured.shape >= SHAPE_ROOF_FIRST && captured.shape <= SHAPE_ROOF_LAST && renderable instanceof Model)
						captured.model = CapturedModel.of((Model) renderable);
					capture.objects.add(captured);
				}
			}

			WallObject wall = tile.getWallObject();
			if (wall != null && wall.getRenderable1() != null)
				add(capture(tile, wall, Kind.WALL, wall.getConfig(), ModelHash.TYPE_WALL_OBJECT));

			DecorativeObject decoration = tile.getDecorativeObject();
			if (decoration != null && decoration.getRenderable() != null) {
				var captured = capture(tile, decoration, Kind.DECORATION, decoration.getConfig(), ModelHash.TYPE_DECORATIVE_OBJECT);
				if (captured != null) {
					captured.xOffset = decoration.getXOffset();
					captured.yOffset = decoration.getYOffset();
					capture.objects.add(captured);
				}
			}

			GroundObject ground = tile.getGroundObject();
			if (ground != null && ground.getRenderable() != null)
				add(capture(tile, ground, Kind.GROUND, ground.getConfig(), ModelHash.TYPE_GROUND_OBJECT));
		}

		/**
		 * Capture a tile's ground as triangles with a colour at each corner, the same way the client gives them to 117 HD.
		 * Painted tiles are two triangles from the tile's corners, while shaped tiles have their own. Like 117 HD, the
		 * tile's heights, underlay & overlay come from the level it's drawn on, which isn't always the one it's kept on.
		 */
		void captureGround(Tile tile) {
			int level = tile.getRenderLevel();
			int[][][] tileHeights = scene.getTileHeights();
			var sceneLocation = tile.getSceneLocation();
			var paint = tile.getSceneTilePaint();
			var model = tile.getSceneTileModel();
			List<Integer> vertices = new ArrayList<>();
			List<Integer> colors = new ArrayList<>();
			List<Integer> textures = new ArrayList<>();
			List<Integer> overrideTypes = new ArrayList<>();
			if (paint != null) {
				if (paint.getNeColor() == HDUtils.HIDDEN_HSL)
					return;
				int tileExX = sceneLocation.getX() + HDUtils.EXTENDED_SCENE_OFFSET;
				int tileExY = sceneLocation.getY() + HDUtils.EXTENDED_SCENE_OFFSET;
				int sw = tileHeights[level][tileExX][tileExY] - groundHeight;
				int se = tileHeights[level][tileExX + 1][tileExY] - groundHeight;
				int ne = tileHeights[level][tileExX + 1][tileExY + 1] - groundHeight;
				int nw = tileHeights[level][tileExX][tileExY + 1] - groundHeight;
				int size = Perspective.LOCAL_TILE_SIZE;
				// Split the same way as 117 HD splits painted tiles
				addCorner(vertices, colors, size, ne, size, paint.getNeColor());
				addCorner(vertices, colors, 0, nw, size, paint.getNwColor());
				addCorner(vertices, colors, size, se, 0, paint.getSeColor());
				addCorner(vertices, colors, 0, sw, 0, paint.getSwColor());
				addCorner(vertices, colors, size, se, 0, paint.getSeColor());
				addCorner(vertices, colors, 0, nw, size, paint.getNwColor());
				for (int triangle = 0; triangle < 2; triangle++) {
					textures.add(paint.getTexture());
					overrideTypes.add(OVERRIDE_MAIN);
				}
			} else if (model != null) {
				int originX = sceneLocation.getX() << Perspective.LOCAL_COORD_BITS;
				int originY = sceneLocation.getY() << Perspective.LOCAL_COORD_BITS;
				int[] vertexX = model.getVertexX();
				int[] vertexY = model.getVertexY();
				int[] vertexZ = model.getVertexZ();
				int[][] faces = { model.getFaceX(), model.getFaceY(), model.getFaceZ() };
				int[][] faceColors = { model.getTriangleColorA(), model.getTriangleColorB(), model.getTriangleColorC() };
				int[] faceTextures = model.getTriangleTextureId();
				for (int face = 0; face < faces[0].length; face++) {
					if (faceColors[0][face] == HDUtils.HIDDEN_HSL)
						continue;
					textures.add(faceTextures == null ? -1 : faceTextures[face]);
					overrideTypes.add(ProceduralGenerator.isOverlayFace(tile, face) ? OVERRIDE_OVERLAY : OVERRIDE_UNDERLAY);
					for (int corner = 0; corner < 3; corner++) {
						int vertex = faces[corner][face];
						addCorner(
							vertices, colors,
							vertexX[vertex] - originX,
							vertexY[vertex] - groundHeight,
							vertexZ[vertex] - originY,
							faceColors[corner][face]
						);
					}
				}
			}
			if (colors.isEmpty())
				return;

			var captured = new CapturedTile();
			captured.x = sceneLocation.getX() - (cornerX >> Perspective.LOCAL_COORD_BITS);
			captured.y = sceneLocation.getY() - (cornerY >> Perspective.LOCAL_COORD_BITS);
			captured.plane = level - originPlane;
			captured.vertices = vertices.stream().mapToInt(Integer::intValue).toArray();
			captured.colors = colors.stream().mapToInt(Integer::intValue).toArray();
			captured.textures = textures.stream().mapToInt(Integer::intValue).toArray();
			captured.overrideTypes = overrideTypes.stream().mapToInt(Integer::intValue).toArray();
			captured.worldPos = new int[3];
			visibility.getWorldPosition(tile, level, captured.worldPos);
			int tileExX = sceneLocation.getX() + HDUtils.EXTENDED_SCENE_OFFSET;
			int tileExY = sceneLocation.getY() + HDUtils.EXTENDED_SCENE_OFFSET;
			captured.underlayId = scene.getUnderlayIds()[level][tileExX][tileExY];
			captured.overlayId = scene.getOverlayIds()[level][tileExX][tileExY];
			capture.tiles.add(captured);
		}

		private static void addCorner(List<Integer> vertices, List<Integer> colors, int x, int height, int y, int color) {
			vertices.add(x);
			vertices.add(height);
			vertices.add(y);
			colors.add(color);
		}

		private void add(@Nullable CapturedObject object) {
			if (object != null)
				capture.objects.add(object);
		}

		@Nullable
		private CapturedObject capture(Tile tile, TileObject object, Kind kind, int config, int type) {
			if (visibility.isHiddenByOverride(object, type))
				return null;

			// Objects which change with varbits are captured as whatever they currently look like
			int id = object.getId();
			var def = client.getObjectDefinition(id);
			if (def.getImpostorIds() != null) {
				var impostor = def.getImpostor();
				if (impostor == null)
					return null;
				id = impostor.getId();
			}

			var captured = new CapturedObject();
			captured.id = id;
			captured.kind = kind;
			captured.shape = config & SHAPE_MASK;
			captured.rotation = config >>> 6 & 3;
			captured.x = object.getX() - cornerX;
			captured.y = object.getY() - cornerY;
			captured.height = object.getZ() - groundHeight;
			// The level it's drawn on, whose ground it follows, which isn't always the one it's kept on
			captured.plane = tile.getRenderLevel() - originPlane;
			return captured;
		}
	}

	/**
	 * Save the tile corner heights under the objects & tiles, with some room around them for big models
	 */
	@Nullable
	private static Terrain captureTerrain(Scene scene, Capture capture, int cornerX, int cornerY, int groundHeight) {
		if (capture.objects.isEmpty() && capture.tiles.isEmpty())
			return null;
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
		for (var object : capture.objects) {
			minX = Math.min(minX, object.x >> Perspective.LOCAL_COORD_BITS);
			minY = Math.min(minY, object.y >> Perspective.LOCAL_COORD_BITS);
			maxX = Math.max(maxX, object.x >> Perspective.LOCAL_COORD_BITS);
			maxY = Math.max(maxY, object.y >> Perspective.LOCAL_COORD_BITS);
		}
		for (var tile : capture.tiles) {
			minX = Math.min(minX, tile.x);
			minY = Math.min(minY, tile.y);
			maxX = Math.max(maxX, tile.x);
			maxY = Math.max(maxY, tile.y);
		}

		int[][][] tileHeights = scene.getTileHeights();
		// Region-relative tiles to extended scene tiles, which have one more corner than tiles along each side
		int toExtendedX = (cornerX >> Perspective.LOCAL_COORD_BITS) + HDUtils.EXTENDED_SCENE_OFFSET;
		int toExtendedY = (cornerY >> Perspective.LOCAL_COORD_BITS) + HDUtils.EXTENDED_SCENE_OFFSET;
		var terrain = new Terrain();
		terrain.x = Math.max(minX - TERRAIN_MARGIN, -toExtendedX);
		terrain.y = Math.max(minY - TERRAIN_MARGIN, -toExtendedY);
		terrain.width = Math.min(maxX + TERRAIN_MARGIN, tileHeights[0].length - 2 - toExtendedX) - terrain.x + 1;
		terrain.height = Math.min(maxY + TERRAIN_MARGIN, tileHeights[0][0].length - 2 - toExtendedY) - terrain.y + 1;
		if (terrain.width <= 0 || terrain.height <= 0)
			return null;

		terrain.heights = new int[tileHeights.length][(terrain.width + 1) * (terrain.height + 1)];
		for (int plane = 0; plane < tileHeights.length; plane++)
			for (int y = 0; y <= terrain.height; y++)
				for (int x = 0; x <= terrain.width; x++)
					terrain.heights[plane][y * (terrain.width + 1) + x] =
						tileHeights[plane][toExtendedX + terrain.x + x][toExtendedY + terrain.y + y] - groundHeight;
		return terrain;
	}

	/**
	 * Finish a recreated object's model the way the client does after rotating it: scale it, move it by its definition's
	 * offset, bend it to the ground, then move it back by the nudge which gave it a unique position. The client does all of
	 * this along the world's axes, so the model is rotated first, and back again after unless it should stay rotated like
	 * static scenery, since RuneLiteObjects rotate when drawn.
	 */
	public static ModelData finishModel(
		ModelData model,
		TestObjectDefinition def,
		int quarterTurns,
		@Nullable Consumer<ModelData> contour,
		int nudge,
		boolean keepRotated
	) {
		model = rotate(model, quarterTurns);
		if (def.scale != null)
			model.scale(def.scale[0], def.scale[1], def.scale[2]);
		if (def.offset != null)
			model.translate(def.offset[0], def.offset[1], def.offset[2]);
		if (contour != null)
			contour.accept(model);
		model.translate(-nudge, 0, 0);
		return keepRotated ? model : rotate(model, 4 - quarterTurns);
	}

	/**
	 * Bend a model which is already rotated like the client's to follow the captured ground, like the client does for
	 * objects marked to, either fully or only its lower part
	 */
	public static void contour(
		ModelData model,
		int contouredGround,
		Terrain terrain,
		int plane,
		int objectX,
		int objectY,
		int objectHeight
	) {
		float[] xs = model.getVerticesX();
		float[] ys = model.getVerticesY();
		float[] zs = model.getVerticesZ();
		int count = model.getVerticesCount();
		float top = 0;
		for (int i = 0; i < count; i++)
			top = Math.max(top, -ys[i]);
		for (int i = 0; i < count; i++) {
			Integer ground = terrain.getHeight(plane, objectX + (int) xs[i], objectY + (int) zs[i]);
			if (ground == null)
				continue;
			int offset = ground - objectHeight;
			if (contouredGround == 0) {
				ys[i] += offset;
			} else if (top > 0) {
				// Only bend the part of the model below the threshold, less so the higher up it is
				int threshold = (int) (-ys[i] * 65536 / top);
				if (threshold < contouredGround)
					ys[i] += (float) offset * (contouredGround - threshold) / contouredGround;
			}
		}
	}

	private static ModelData rotate(ModelData model, int quarterTurns) {
		switch (quarterTurns & 3) {
			case 1:
				return model.rotateY90Ccw();
			case 2:
				return model.rotateY180Ccw();
			case 3:
				return model.rotateY270Ccw();
			default:
				return model;
		}
	}

	/**
	 * The roof a tile's objects are hidden with, worked out the same way as 117 HD's SceneUploader
	 */
	private static int getRoofId(byte[][][] settings, int[][][] roofs, int level, int extendedX, int extendedY) {
		int mapLevel = level;
		if ((settings[1][extendedX][extendedY] & Constants.TILE_FLAG_BRIDGE) != 0)
			++mapLevel;
		boolean visibleBelow = mapLevel <= 3 && (settings[mapLevel][extendedX][extendedY] & Constants.TILE_FLAG_VIS_BELOW) != 0;
		if (visibleBelow || mapLevel == 0)
			return 0;
		return roofs[mapLevel - 1][extendedX][extendedY];
	}

	/**
	 * The local coordinate of the south-west edge of the region containing a local coordinate, along one axis
	 */
	public static int getRegionCorner(int sceneBase, int local) {
		return ((sceneBase + (local >> Perspective.LOCAL_COORD_BITS) & ~63) - sceneBase) << Perspective.LOCAL_COORD_BITS;
	}

	private static int getRegionId(Scene scene, int sceneX, int sceneY) {
		return (scene.getBaseX() + sceneX >> 6) << 8 | scene.getBaseY() + sceneY >> 6;
	}

	/**
	 * The models the client builds for an object of this kind, shape & rotation, which most objects only have one of
	 */
	public static List<Part> getParts(CapturedObject object) {
		int rotation = object.rotation;
		switch (object.kind) {
			case GROUND:
				return List.of(new Part(SHAPE_GROUND_DECORATION, rotation, false));
			case WALL:
				if (object.shape == SHAPE_WALL_CORNER)
					return List.of(new Part(SHAPE_WALL_CORNER, rotation + 4, false), new Part(SHAPE_WALL_CORNER, rotation + 1 & 3, false));
				return List.of(new Part(object.shape, rotation, false));
			case DECORATION:
				switch (object.shape) {
					case SHAPE_DECORATION_DIAGONAL_OFFSET:
						return List.of(new Part(SHAPE_DECORATION_STRAIGHT, rotation + 4, true));
					case SHAPE_DECORATION_DIAGONAL_NO_OFFSET:
						return List.of(new Part(SHAPE_DECORATION_STRAIGHT, (rotation + 2 & 3) + 4, true));
					case SHAPE_DECORATION_DIAGONAL_BOTH:
						return List.of(
							new Part(SHAPE_DECORATION_STRAIGHT, rotation + 4, true),
							new Part(SHAPE_DECORATION_STRAIGHT, (rotation + 2 & 3) + 4, false)
						);
					default:
						return List.of(new Part(SHAPE_DECORATION_STRAIGHT, rotation, true));
				}
			case GAME:
			default:
				return List.of(new Part(object.shape == SHAPE_CENTREPIECE_DIAGONAL ? SHAPE_CENTREPIECE : object.shape, rotation, false));
		}
	}

	/**
	 * The model IDs for a part, or null if the object has no model of that type.
	 * Objects without model types only have centrepiece models, which the client merges.
	 */
	@Nullable
	public static int[] getModelIds(TestObjectDefinition def, Part part) {
		if (def.modelTypes == null)
			return part.modelType == SHAPE_CENTREPIECE ? def.allModels : null;
		for (int i = 0; i < def.modelTypes.length; i++)
			if (def.modelTypes[i] == part.modelType)
				return new int[] { def.allModels[i] };
		return null;
	}

	/**
	 * Mirror a model the way the client does, which also flips its faces' winding so they don't turn inside out.
	 * Only call this on a model from {@link Client#mergeModels}, since it changes the face arrays in place.
	 */
	public static void mirror(ModelData model) {
		float[] z = model.getVerticesZ();
		for (int i = 0; i < model.getVerticesCount(); i++)
			z[i] = -z[i];
		int[] a = model.getFaceIndices1();
		int[] c = model.getFaceIndices3();
		for (int i = 0; i < model.getFaceCount(); i++) {
			int tmp = a[i];
			a[i] = c[i];
			c[i] = tmp;
		}
	}

	/**
	 * Turn a wall decoration model 45 degrees to sit against a diagonal wall, and move it out to the wall like the client does
	 */
	public static void rotateDiagonal(ModelData model) {
		float[] x = model.getVerticesX();
		float[] z = model.getVerticesZ();
		float sin = (float) Math.sin(Math.PI / 4);
		for (int i = 0; i < model.getVerticesCount(); i++) {
			float rotatedX = z[i] * sin + x[i] * sin;
			z[i] = z[i] * sin - x[i] * sin;
			x[i] = rotatedX;
		}
		model.translate(45, 0, -45);
	}

	/**
	 * Save a capture under a file name made from its name, without replacing an earlier capture. Returns the file name.
	 */
	public static String save(Gson gson, Capture capture) throws IOException {
		CAPTURES_PATH.mkdirs();
		String baseName = capture.name.replaceAll("[^A-Za-z0-9 _-]", "_").trim();
		if (baseName.isEmpty())
			baseName = getDefaultName(null);
		String fileName = baseName + ".json";
		for (int i = 2; CAPTURES_PATH.resolve(fileName).exists(); i++)
			fileName = baseName + " (" + i + ").json";
		CAPTURES_PATH.resolve(fileName).writeString(gson.toJson(capture));
		return fileName;
	}

	/**
	 * A name for a capture which wasn't given one, from where it was captured, such as an environment, and when
	 */
	public static String getDefaultName(@Nullable String place) {
		return (place == null ? "capture" : place) + "_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
	}

	public static Capture load(Gson gson, String fileName) throws IOException {
		return CAPTURES_PATH.resolve(fileName).loadJson(gson, Capture.class);
	}

	/**
	 * The file names of every saved capture, sorted by name
	 */
	public static List<String> list() throws IOException {
		if (!CAPTURES_PATH.exists())
			return List.of();
		try (Stream<Path> files = Files.list(CAPTURES_PATH.toPath())) {
			return files
				.map(path -> path.getFileName().toString())
				.filter(name -> name.endsWith(".json"))
				.sorted(String.CASE_INSENSITIVE_ORDER)
				.collect(Collectors.toList());
		}
	}
}
