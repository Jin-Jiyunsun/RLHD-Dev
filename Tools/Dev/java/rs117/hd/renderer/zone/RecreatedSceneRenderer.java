package rs117.hd.renderer.zone;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.hooks.*;
import rs117.hd.HdPlugin;
import rs117.hd.config.ShadowMode;
import rs117.hd.dev.Reflection;
import rs117.hd.renderer.SkyRenderer;
import rs117.hd.scene.MaterialManager;
import rs117.hd.scene.ModelOverrideManager;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.TileOverrideManager;
import rs117.hd.scene.ground_materials.GroundMaterial;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.scene.model_overrides.UvType;
import rs117.hd.scene.tile_overrides.TileOverride;
import rs117.hd.scene.water_types.WaterType;
import rs117.hd.utils.DestructibleHandler;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.VariableSupplier;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.GLTextureBuffer;
import rs117.hd.utils.buffer.GpuIntBuffer;
import rs117.hd.utils.jobs.GenericJob;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;
import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.dev.Reflection.field;
import static rs117.hd.dev.Reflection.method;
import static rs117.hd.renderer.zone.SceneManager.NUM_ZONES;
import static rs117.hd.scene.tile_overrides.TileOverride.NONE;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.buffer.GLBuffer.MAP_WRITE;

/**
 * Developer tools: draws a recreated scene capture's static objects, NPCs and floor at full speed, without changes to 117 HD.
 * <p>
 * They're uploaded into zones of their own, the same way 117 HD uploads static scenery, and drawn right after 117 HD draws
 * the real zone in the same place, using the top-level scene's levels, roofs and culling. This lives in 117 HD's renderer
 * package so it can use the package-private parts of zones, and calls into 117 HD's SceneUploader through reflection.
 */
@Slf4j
@Singleton
public class RecreatedSceneRenderer {
	// Spread starting uploads of big scenes over a few ticks, to avoid a hitch
	private static final long UPLOAD_NANOS_PER_TICK = 6_000_000;
	// Zones being filled on 117 HD's worker threads at once
	private static final int MAX_UPLOADS_IN_FLIGHT = 16;

	private static final Field BASE_X = field(SceneUploader.class, "basex");
	private static final Field BASE_Z = field(SceneUploader.class, "basez");
	private static final Field RID = field(SceneUploader.class, "rid");
	private static final Field RENDERABLE_PLANE = field(SceneUploader.class, "renderablePlane");
	private static final Field WORLD_POS = field(SceneUploader.class, "worldPos");
	private static final Method UPLOAD_ZONE_RENDERABLE = method(
		SceneUploader.class,
		"uploadZoneRenderable",
		ZoneSceneContext.class, Zone.class, Tile.class, Renderable.class,
		int.class, int.class, int.class, int.class, int.class, int.class,
		int.class, int.class, int.class, int.class, int.class,
		int.class, int.class, int.class,
		GpuIntBuffer.class, GpuIntBuffer.class, GpuIntBuffer.class
	);
	private static final Field SKY_RENDERER = field(ZoneRenderer.class, "skyRenderer");
	private static final Field FAST_SHADOW_PROGRAM = field(ZoneRenderer.class, "fastShadowProgram");
	private static final Field DETAILED_SHADOW_PROGRAM = field(ZoneRenderer.class, "detailedShadowProgram");
	private static final Field SHOULD_DRAW_ROOF_SHADOWS = field(ZoneRenderer.class, "shouldDrawRoofShadows");
	private static final Field SCENE_PROGRAM = field(ZoneRenderer.class, "sceneProgram");
	private static final Field WATER_PROGRAM = field(ZoneRenderer.class, "waterProgram");
	private static final short[] UP_NORMAL = { 0, -1, 0 };

	@Inject
	private Client client;

	@Inject
	private HdPlugin plugin;

	@Inject
	private SceneManager sceneManager;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	@Inject
	private MaterialManager materialManager;

	@Inject
	private TileOverrideManager tileOverrideManager;

	/**
	 * A recreated object or NPC, uploaded like static scenery. Its model is already rotated the way the client rotates
	 * the object it stands in for.
	 */
	@RequiredArgsConstructor
	public static class StaticObject {
		// The ModelHash type it stands in for, an object or NPC, which picks its model overrides & lights
		public final int type;
		// The object or NPC ID
		public final int id;
		public final Model model;
		// The same orientations 117 HD gets for the real object: before & while being drawn
		public final int preOrientation;
		public final int orientation;
		// For the object's lights, which real objects orient by their rotation
		public final int lightOrientation;
		public final int plane;
		// Local coordinates in the scene
		public final int x;
		public final int y;
		public final int height;
		public volatile boolean removed;
	}

	/**
	 * A tile of a recreated scene capture's floor, drawn in place of the real tile it lands on
	 */
	@RequiredArgsConstructor
	public static class FloorTile {
		// The real tile it replaces, in the extended scene
		public final int plane;
		public final int tileExX;
		public final int tileExY;
		// Three corners per triangle, each as a position within the tile & a height, with a colour for each corner
		public final int[] vertices;
		public final int[] colors;
		// What picks the tile's tile overrides each time it's uploaded
		@Nullable
		public final int[] worldPos;
		public final int underlayId;
		public final int overlayId;
		// For each triangle: its vanilla texture or -1, and which of the tile's overrides it uses
		@Nullable
		public final int[] textures;
		@Nullable
		public final int[] overrideTypes;
	}

	private static class ZoneContent {
		final List<StaticObject> objects = new ArrayList<>();
		final List<FloorTile> floor = new ArrayList<>();
		// Holes in the ground, drawn black behind everything like 117 HD's gap fillers
		final List<FloorTile> gapFillers = new ArrayList<>();
	}

	private final Map<Integer, ZoneContent> contents = new HashMap<>();
	// Whether 117 HD's own gap fillers are left out, such as while everything but test objects is hidden
	@Getter
	@Setter
	private boolean hidingRealGapFillers;
	// The real zone whose gap fillers are switched off while 117 HD draws it
	@Nullable
	private Zone realZoneWithoutGapFillers;
	private final Zone[][] zones = new Zone[NUM_ZONES][NUM_ZONES];
	private final ArrayDeque<Integer> pendingZones = new ArrayDeque<>();
	// Zones being filled on 117 HD's worker threads, like its own ZoneUploadJobs, with their buffers mapped & unmapped on
	// the client thread. Running as 117 HD's jobs means it waits for them before tearing its renderer down.
	private final List<ZoneUpload> uploadsInFlight = new ArrayList<>();
	// Goes up each time everything is uploaded again, so zones from an earlier upload still in flight are thrown away
	private int uploadGeneration;
	private long uploadStartedAt;
	// The floor's blending, being worked out on a worker thread
	@Nullable
	private GenericJob blendingJob;
	@Nullable
	private volatile RecreatedFloorBlending blendingResult;
	// The renderer the zones were uploaded for, since they're gone if 117 HD has started a new one since
	@Nullable
	private Object uploadedFor;
	// The ground data 117 HD had generated when the zones were uploaded. It generates it again when it reloads its scene, such
	// as when tile or model overrides or ground settings change, which keeps the scene context, so look out for new data.
	@Nullable
	private Object uploadedForSceneData;
	private List<FloorTile> floor = List.of();
	// Worked out from the whole floor before uploading it, and again whenever everything is uploaded again
	@Nullable
	private RecreatedFloorBlending floorBlending;

	/**
	 * Replace what's recreated. Must be called on the client thread.
	 */
	public void set(List<StaticObject> objects, List<FloorTile> floor, List<FloorTile> gapFillers) {
		assert client.isClientThread();
		clear();
		int sceneOffset = HDUtils.EXTENDED_SCENE_OFFSET;
		for (var object : objects) {
			int zoneKey = packZone(((object.x >> LOCAL_COORD_BITS) + sceneOffset) >> 3, ((object.y >> LOCAL_COORD_BITS) + sceneOffset) >> 3);
			contents.computeIfAbsent(zoneKey, k -> new ZoneContent()).objects.add(object);
		}
		for (var tile : floor)
			contents.computeIfAbsent(packZone(tile.tileExX >> 3, tile.tileExY >> 3), k -> new ZoneContent()).floor.add(tile);
		for (var tile : gapFillers)
			contents.computeIfAbsent(packZone(tile.tileExX >> 3, tile.tileExY >> 3), k -> new ZoneContent()).gapFillers.add(tile);
		this.floor = floor;
		uploadAll();
	}

	public boolean hasContent() {
		return !contents.isEmpty();
	}

	public boolean isUploading() {
		return !pendingZones.isEmpty() || !uploadsInFlight.isEmpty() || floorBlending == null && !contents.isEmpty();
	}

	/**
	 * Remove what's recreated. Must be called on the client thread.
	 */
	public void clear() {
		assert client.isClientThread();
		boolean rendererAlive = plugin.renderer != null && plugin.renderer == uploadedFor;
		dropUploadsInFlight(rendererAlive);
		for (var column : zones) {
			for (int i = 0; i < column.length; i++) {
				if (column[i] == null)
					continue;
				if (rendererAlive) {
					DestructibleHandler.queueDestruction(column[i]);
				} else {
					forget(column[i]);
				}
				column[i] = null;
			}
		}
		contents.clear();
		pendingZones.clear();
		floor = List.of();
		floorBlending = null;
		uploadedForSceneData = null;
	}

	private void uploadAll() {
		pendingZones.clear();
		pendingZones.addAll(contents.keySet());
		floorBlending = null;
		blendingJob = null;
		blendingResult = null;
		uploadGeneration++;
		uploadStartedAt = System.nanoTime();
	}

	/**
	 * Throw away zones still being filled, waiting for them if their buffers are still alive, or leaving them to 117 HD
	 * otherwise, which has already cancelled them while tearing its renderer down
	 */
	private void dropUploadsInFlight(boolean rendererAlive) {
		for (var upload : uploadsInFlight) {
			if (rendererAlive) {
				upload.job.waitForCompletion();
				upload.zone.unmap();
				DestructibleHandler.queueDestruction(upload.zone);
			} else {
				forget(upload.zone);
			}
		}
		uploadsInFlight.clear();
	}

	/**
	 * Upload what's waiting to be, a few zones per tick. Must be called on the client thread every tick.
	 */
	public void update() {
		if (contents.isEmpty())
			return;

		// 117 HD's zones and their buffers go when it starts a new renderer, such as when it restarts, so upload ours again
		var renderer = plugin.renderer;
		if (renderer != uploadedFor) {
			dropUploadsInFlight(false);
			for (var column : zones) {
				for (int i = 0; i < column.length; i++) {
					if (column[i] != null)
						forget(column[i]);
					column[i] = null;
				}
			}
			uploadedFor = renderer;
			uploadAll();
		}
		if (!(renderer instanceof ZoneRenderer))
			return;

		var root = sceneManager.getRoot();
		if (root == null || root.sceneContext == null || root.isLoading)
			return;

		// Pick up changes to tile & model overrides and ground settings, which 117 HD reloads its scene for
		if (root.sceneContext.vertexTerrainColor != uploadedForSceneData) {
			uploadedForSceneData = root.sceneContext.vertexTerrainColor;
			uploadAll();
		}
		finishUploads();
		if (floorBlending == null && !updateBlending())
			return;

		// Size each zone & map its buffers here, then fill them on worker threads
		long deadline = System.nanoTime() + UPLOAD_NANOS_PER_TICK;
		while (!pendingZones.isEmpty() && uploadsInFlight.size() < MAX_UPLOADS_IN_FLIGHT && System.nanoTime() < deadline) {
			int key = pendingZones.poll();
			var content = contents.get(key);
			if (content == null)
				continue;
			int zx = key >> 16, zz = key & 0xFFFF;
			try {
				var zone = startZoneUpload(root, zx, zz, content);
				if (zone == null) {
					if (zones[zx][zz] != null)
						DestructibleHandler.queueDestruction(zones[zx][zz]);
					zones[zx][zz] = null;
					continue;
				}
				var upload = new ZoneUpload(zx, zz, zone, uploadGeneration);
				var blending = floorBlending;
				upload.job = GenericJob.build("Recreated zone upload", job -> {
					try {
						fillZone(root, zx, zz, content, zone, blending);
					} catch (Throwable ex) {
						upload.error = ex;
					}
				}).queue(false);
				uploadsInFlight.add(upload);
			} catch (Exception ex) {
				log.error("Unable to upload recreated zone [{}, {}]", zx, zz, ex);
			}
		}
		if (pendingZones.isEmpty() && uploadsInFlight.isEmpty() && uploadStartedAt != 0) {
			log.debug("Uploaded the recreated scene's zones in {} ms", (System.nanoTime() - uploadStartedAt) / 1_000_000);
			uploadStartedAt = 0;
		}
	}

	/**
	 * Work out the floor's blending on a worker thread, returning whether it's ready. The tile overrides are picked here
	 * though, since looking them up changes them.
	 */
	private boolean updateBlending() {
		if (blendingJob == null) {
			var overrides = new IdentityHashMap<FloorTile, TileOverride[]>();
			var overlayMainTiles = Collections.newSetFromMap(new IdentityHashMap<FloorTile, Boolean>());
			resolveFloorOverrides(overrides, overlayMainTiles);
			var proceduralGenerator = plugin.getInjector().getInstance(ProceduralGenerator.class);
			var tiles = floor;
			long startedAt = System.nanoTime();
			blendingJob = GenericJob.build("Recreated floor blending", job -> {
				blendingResult = new RecreatedFloorBlending(tiles, overrides, overlayMainTiles, proceduralGenerator::seasonalWaterType);
				log.debug("Blended the recreated floor in {} ms", (System.nanoTime() - startedAt) / 1_000_000);
			}).queue(false);
		}
		if (!blendingJob.isDone())
			return false;
		var job = blendingJob;
		floorBlending = blendingResult;
		job.release();
		if (floorBlending == null) {
			// It failed, which 117 HD has logged, so try again next time
			blendingJob = null;
			return false;
		}
		return true;
	}

	/**
	 * Put the zones which have finished filling in place, once their buffers are unmapped
	 */
	private void finishUploads() {
		for (var it = uploadsInFlight.iterator(); it.hasNext(); ) {
			var upload = it.next();
			if (!upload.job.isDone())
				continue;
			it.remove();
			upload.job.release();
			var zone = upload.zone;
			zone.unmap();
			if (upload.error != null || upload.generation != uploadGeneration) {
				if (upload.error != null)
					log.error("Unable to upload recreated zone [{}, {}]", upload.zx, upload.zz, upload.error);
				DestructibleHandler.queueDestruction(zone);
				continue;
			}
			if (zone.minY == Integer.MAX_VALUE)
				zone.minY = zone.maxY = 0;
			zone.initialized = true;
			if (zones[upload.zx][upload.zz] != null)
				DestructibleHandler.queueDestruction(zones[upload.zx][upload.zz]);
			zones[upload.zx][upload.zz] = zone;
		}
	}

	@RequiredArgsConstructor
	private static class ZoneUpload {
		final int zx;
		final int zz;
		final Zone zone;
		final int generation;
		GenericJob job;
		volatile Throwable error;
	}

	/**
	 * Whether the top-level scene's objects at this level, under this roof, were drawn last frame, or null if nothing
	 * has been drawn yet. Must be called on the client thread.
	 */
	@Nullable
	public Boolean isLevelDrawn(int level, int roofId) {
		var root = sceneManager.getRoot();
		if (root == null || root.sceneContext == null || root.hideRoofIds == null)
			return null;
		return level >= root.minLevel && level <= root.maxLevel &&
			   !(level > root.level && root.hideRoofIds.contains(roofId));
	}

	/**
	 * Size a zone and map its buffers, which has to happen on the client thread, or return null if it has nothing to draw
	 */
	@Nullable
	private Zone startZoneUpload(WorldViewContext root, int zx, int zz, ZoneContent content) {
		var ctx = root.sceneContext;
		int[] worldPos = new int[3];

		// Work out how much room the zone needs, like SceneUploader.estimateZoneSize
		Zone zone = plugin.getInjector().getInstance(Zone.class);
		for (var object : content.objects) {
			ctx.localToWorld(object.x, object.y, object.plane, worldPos);
			var override = modelOverrideManager.getOverride(ModelHash.packUuid(object.type, object.id), worldPos);
			if (!override.hide)
				estimateModelSize(zone, object.model, override);
		}
		int waterTriangles = 0;
		for (var tile : content.floor) {
			zone.sizeO += tile.colors.length / 3;
			zone.sizeF += tile.colors.length / 3;
			for (int triangle = 0; triangle < tile.colors.length / 3; triangle++)
				if (floorBlending.getWaterType(tile, triangle) != WaterType.NONE)
					waterTriangles++;
		}
		// The water's surface is drawn on top of the sunken ground under it
		zone.sizeO += waterTriangles;
		zone.sizeF += waterTriangles;
		zone.hasWater = waterTriangles > 0;
		for (var tile : content.gapFillers) {
			zone.sizeO += tile.vertices.length / 9;
			zone.sizeF += tile.vertices.length / 9;
			zone.hasGapFiller = true;
		}
		if (zone.sizeO == 0)
			return null;

		// Map the buffers like ZoneUploadJob does
		var vboO = new GLBuffer("Zone::VBO::Opaque::Recreated", GL_ARRAY_BUFFER, GL_STATIC_DRAW);
		vboO.initialize(zone.sizeO * Zone.VERT_SIZE * 3);
		vboO.map(MAP_WRITE);
		GLBuffer vboA = null;
		if (zone.sizeA > 0) {
			vboA = new GLBuffer("Zone::VBO::Alpha::Recreated", GL_ARRAY_BUFFER, GL_STATIC_DRAW);
			vboA.initialize(zone.sizeA * Zone.VERT_SIZE * 3);
			vboA.map(MAP_WRITE);
		}
		var tboF = new GLTextureBuffer("Zone::TBO::Recreated", GL_STATIC_DRAW);
		tboF.initialize(zone.sizeF * Zone.TEXTURE_SIZE);
		tboF.map(MAP_WRITE);
		zone.initialize(vboO, vboA, tboF);
		zone.setMetadata(root, ctx, zx, zz);
		return zone;
	}

	/**
	 * Fill a zone's mapped buffers. Runs on a worker thread, reading only what doesn't change while it's in flight.
	 */
	private void fillZone(
		WorldViewContext root,
		int zx,
		int zz,
		ZoneContent content,
		Zone zone,
		RecreatedFloorBlending floorBlending
	) throws ReflectiveOperationException {
		var ctx = root.sceneContext;
		var scene = ctx.scene;
		var tiles = scene.getExtendedTiles();

		// Upload like SceneUploader.uploadZone, without roofs to hide, putting objects on their levels and the floor with
		// the terrain
		zone.minX = zone.minZ = 0;
		zone.maxX = zone.maxZ = CHUNK_SIZE * LOCAL_TILE_SIZE;
		zone.minY = Integer.MAX_VALUE;
		zone.maxY = Integer.MIN_VALUE;
		zone.rids = new int[4][0];
		zone.roofStart = new int[4][0];
		zone.roofEnd = new int[4][0];

		var vb = new GpuIntBuffer(false).setBuffer(zone.vboO.mapped());
		var ab = zone.vboA == null ? null : new GpuIntBuffer(false).setBuffer(zone.vboA.mapped());
		var fb = new GpuIntBuffer(false).setBuffer(zone.tboF.mapped());
		int basex = (zx - (ctx.sceneOffset >> 3)) << 10;
		int basez = (zz - (ctx.sceneOffset >> 3)) << 10;
		try (SceneUploader uploader = SceneUploader.POOL.acquire()) {
			uploader.setScene(scene);
			if (uploader.writeCache == null)
				uploader.writeCache = new VertexWriteCache.Collection();
			BASE_X.setInt(uploader, basex);
			BASE_Z.setInt(uploader, basez);
			RID.setInt(uploader, 0);
			int[] uploaderWorldPos = (int[]) WORLD_POS.get(uploader);
			Map<Integer, List<FloorTile>> floorByTile = new HashMap<>();
			for (var tile : content.floor)
				floorByTile.computeIfAbsent(packTile(tile.plane, tile.tileExX, tile.tileExY), k -> new ArrayList<>()).add(tile);
			TileOverride[] realOverrides = new TileOverride[3];

			for (int level = 0; level <= 3; level++) {
				RENDERABLE_PLANE.setInt(uploader, level);
				for (var object : content.objects) {
					if (object.plane != level)
						continue;
					int tileExX = (object.x >> LOCAL_COORD_BITS) + ctx.sceneOffset;
					int tileExY = (object.y >> LOCAL_COORD_BITS) + ctx.sceneOffset;
					Tile tile = tiles[level][tileExX][tileExY];
					if (tile == null)
						continue;
					int tileZ = tile.getRenderLevel();
					ctx.localToWorld(object.x, object.y, level, uploaderWorldPos);

					// Objects which inherit the tile's colour take it from the recreated floor, rather than the real tile it
					// hides, so for the upload, the real tile's overrides are swapped for the recreated floor's too
					var floor = findFloorTile(floorByTile.get(packTile(tileZ, tileExX, tileExY)), object.height);
					var floorOverrides = floor == null ? null : floorBlending.getOverrides(floor);
					boolean swapOverrides = floorOverrides != null;
					if (swapOverrides) {
						ctx.getTileOverrides(tileZ, tileExX, tileExY, realOverrides);
						ctx.setTileOverride(tileZ, tileExX, tileExY, floorOverrides);
					}
					try {
						UPLOAD_ZONE_RENDERABLE.invoke(
							uploader,
							ctx, zone, floor == null ? tile : standInTile(tile, floor), object.model,
							ModelHash.packUuid(object.type, object.id),
							object.preOrientation, object.orientation,
							object.x, object.height, object.y,
							-1, -1, -1, -1,
							object.id,
							tileExX, tileExY, tileZ,
							vb, ab, fb
						);
					} catch (InvocationTargetException ex) {
						log.warn("Unable to upload recreated object {}", object.id, ex.getCause());
					} finally {
						if (swapOverrides)
							ctx.setTileOverride(tileZ, tileExX, tileExY, realOverrides);
					}
				}
				zone.levelOffsets[level] = vb.position();
			}

			uploadFloor(uploader, ctx, floorBlending, content.floor, basex, basez, vb, fb);
			zone.levelOffsets[Zone.LEVEL_TERRAIN] = vb.position();
			if (zone.hasWater)
				uploadWaterSurface(uploader, ctx, floorBlending, content.floor, basex, basez, vb, fb);
			zone.levelOffsets[Zone.LEVEL_WATER_SURFACE] = vb.position();
			if (zone.hasGapFiller)
				uploadGapFillers(uploader, ctx, content.gapFillers, basex, basez, vb, fb);
			zone.levelOffsets[Zone.LEVEL_GAP_FILLER] = vb.position();
		}
	}

	/**
	 * The same as SceneUploader.estimateRenderableSize, for a model
	 */
	private static void estimateModelSize(Zone zone, Model model, ModelOverride modelOverride) {
		int faceCount = model.getFaceCount();
		boolean isVanillaTextured = model.getFaceTextures() != null;
		zone.sizeF += faceCount;
		if (modelOverride.mightBeDoubleSided || isVanillaTextured)
			faceCount *= 2;
		zone.sizeO += faceCount;
		if (modelOverride.mightHaveTransparency || model.getFaceTransparencies() != null || isVanillaTextured || model.getTransparency() != 0)
			zone.sizeA += faceCount;
		zone.modelCount++;
	}

	/**
	 * Draw a recreated scene capture's floor tiles from their triangles, like SceneUploader.uploadTilePaint & uploadTileModel
	 * draw real tiles. Their tile overrides are picked from where the tiles really are, with their replacements picked by
	 * the captured tile's colour, so edits to tile_overrides.json show up when the floor is uploaded again. Water is drawn
	 * as the ground under it, sunk deeper the further it is from the shore, with its surface uploaded separately.
	 */
	private void uploadFloor(
		SceneUploader uploader,
		ZoneSceneContext ctx,
		RecreatedFloorBlending blending,
		List<FloorTile> floor,
		int basex,
		int basez,
		GpuIntBuffer vertexBuffer,
		GpuIntBuffer textureBuffer
	) {
		if (floor.isEmpty())
			return;
		// From 117 HD's own injector, since 117 HD doesn't create it up front for the developer plugin to be given
		var proceduralGenerator = plugin.getInjector().getInstance(ProceduralGenerator.class);
		if (uploader.writeCache == null)
			uploader.writeCache = new VertexWriteCache.Collection();
		var writeCache = uploader.writeCache;
		writeCache.setOutputBuffers(vertexBuffer, vertexBuffer, textureBuffer);
		final var vb = writeCache.getVertexBuffer();
		final var tb = writeCache.getTextureBuffer();

		Material[] materials = new Material[3];
		int[] colors = new int[3];
		long[] corners = new long[3];
		short[][] normals = new short[3][3];
		boolean[] overlayEdges = new boolean[3];
		int[] terrainData = new int[3];
		int[] heightOffsets = new int[3];
		for (var tile : floor) {
			int baseX = (tile.tileExX - ctx.sceneOffset) * LOCAL_TILE_SIZE - basex;
			int baseZ = (tile.tileExY - ctx.sceneOffset) * LOCAL_TILE_SIZE - basez;
			int[] v = tile.vertices;
			int[] wp = tile.worldPos;
			TileOverride[] overrides = blending.getOverrides(tile);
			boolean painted = RecreatedFloorBlending.isPainted(tile);

			for (int t = 0, i = 0; i < tile.colors.length; t++, i += 3) {
				int textureId = tile.textures == null ? -1 : tile.textures[t];
				int type = tile.overrideTypes == null ? 0 : tile.overrideTypes[t];
				var override = overrides == null ? NONE : overrides[type];
				boolean isOverlay = painted ? blending.isMainOverlay(tile) : type == 2;
				System.arraycopy(tile.colors, i, colors, 0, 3);
				materials[0] = materials[1] = materials[2] = Material.NONE;
				for (int k = 0; k < 3; k++) {
					corners[k] = RecreatedFloorBlending.cornerKey(tile, t * 3 + k);
					blending.getNormal(corners[k], normals[k], UP_NORMAL);
				}
				int a = i * 3;

				var waterType = blending.getWaterType(tile, t);
				if (waterType != WaterType.NONE) {
					// The ground under the water, like SceneUploader draws it
					for (int k = 0; k < 3; k++) {
						int depth = blending.getUnderwaterDepth(corners[k]);
						colors[k] = HDUtils.UNDERWATER_HSL;
						if (plugin.configGroundTextures && wp != null) {
							materials[k] = GroundMaterial.UNDERWATER_GENERIC.getRandomMaterial(
								wp[0] + (v[a + k * 3] >> LOCAL_COORD_BITS),
								wp[1] + (v[a + k * 3 + 2] >> LOCAL_COORD_BITS),
								wp[2]
							);
						}
						overlayEdges[k] = false;
						terrainData[k] = HDUtils.packTerrainData(true, max(1, depth), waterType, tile.plane);
						heightOffsets[k] = depth - override.heightOffset;
					}
					putTriangle(vb, tb, tile, a, baseX, baseZ, colors, materials, overlayEdges, terrainData, heightOffsets, 0, 1, normals);
					continue;
				}

				if (textureId != -1) {
					var material = materialManager.fromVanillaTexture(textureId);
					// Disable tile overrides for newly introduced vanilla textures
					if (material.isFallbackVanillaMaterial)
						override = NONE;
					materials[0] = materials[1] = materials[2] = material;
				}

				boolean keepsOwnColor = RecreatedFloorBlending.useDefaultColor(tile, t, override);
				boolean allowBlending =
					plugin.configGroundBlending &&
					textureId == -1 &&
					!(painted ? keepsOwnColor : isOverlay && keepsOwnColor);
				boolean blendColors = plugin.configGroundBlendingColors && allowBlending;
				boolean blendTextures = plugin.configGroundBlendingTextures && allowBlending;

				int uvOrientation = 0;
				float uvScale = 1;
				GroundMaterial groundMaterial = null;
				if (override != NONE) {
					groundMaterial = override.groundMaterial;
					uvOrientation = override.uvOrientation;
					uvScale = override.uvScale;
					if (!blendColors)
						for (int k = 0; k < 3; k++)
							colors[k] = override.modifyColor(colors[k]);
				} else if (textureId == -1 && wp != null) {
					// Fall back to the default ground material if the tile is untextured
					groundMaterial = override.groundMaterial;
				}

				if (blendColors)
					for (int k = 0; k < 3; k++)
						colors[k] = blending.getColor(corners[k], colors[k]);

				if (plugin.configGroundTextures) {
					if (blendTextures) {
						for (int k = 0; k < 3; k++)
							materials[k] = blending.getMaterial(corners[k], materials[k]);
					} else if (groundMaterial != null && wp != null) {
						for (int k = 0; k < 3; k++) {
							materials[k] = groundMaterial.getRandomMaterial(
								wp[0] + (v[a + k * 3] >> LOCAL_COORD_BITS),
								wp[1] + (v[a + k * 3 + 2] >> LOCAL_COORD_BITS),
								wp[2]
							);
						}
					}
				}

				for (int k = 0; k < 3; k++) {
					overlayEdges[k] = blending.isOverlayEdge(corners[k]);
					terrainData[k] = HDUtils.packTerrainData(true, 0, WaterType.NONE, tile.plane);
					heightOffsets[k] = -override.heightOffset;
				}
				putTriangle(vb, tb, tile, a, baseX, baseZ, colors, materials, overlayEdges, terrainData, heightOffsets, uvOrientation, uvScale, normals);
			}
		}
		writeCache.release();
	}

	/**
	 * Draw a recreated floor's water surface, like SceneUploader draws real water's, with foam where it meets land
	 */
	private void uploadWaterSurface(
		SceneUploader uploader,
		ZoneSceneContext ctx,
		RecreatedFloorBlending blending,
		List<FloorTile> floor,
		int basex,
		int basez,
		GpuIntBuffer vertexBuffer,
		GpuIntBuffer textureBuffer
	) {
		assert uploader.writeCache != null;
		var writeCache = uploader.writeCache;
		writeCache.setOutputBuffers(vertexBuffer, vertexBuffer, textureBuffer);
		final var vb = writeCache.getVertexBuffer();
		final var tb = writeCache.getTextureBuffer();

		Material[] materials = { Material.NONE, Material.NONE, Material.NONE };
		boolean[] overlayEdges = new boolean[3];
		int[] colors = new int[3];
		int[] terrainData = new int[3];
		int[] heightOffsets = new int[3];
		short[][] normals = { UP_NORMAL, UP_NORMAL, UP_NORMAL };
		for (var tile : floor) {
			int baseX = (tile.tileExX - ctx.sceneOffset) * LOCAL_TILE_SIZE - basex;
			int baseZ = (tile.tileExY - ctx.sceneOffset) * LOCAL_TILE_SIZE - basez;
			TileOverride[] overrides = blending.getOverrides(tile);
			for (int t = 0, i = 0; i < tile.colors.length; t++, i += 3) {
				var waterType = blending.getWaterType(tile, t);
				if (waterType == WaterType.NONE)
					continue;
				int type = tile.overrideTypes == null ? 0 : tile.overrideTypes[t];
				var override = overrides == null ? NONE : overrides[type];
				// The water shader draws foam where the colour is 0
				for (int k = 0; k < 3; k++) {
					colors[k] = blending.isShore(RecreatedFloorBlending.cornerKey(tile, t * 3 + k)) ? 0 : 127;
					terrainData[k] = HDUtils.packTerrainData(true, 0, waterType, tile.plane);
					heightOffsets[k] = -override.heightOffset;
				}
				// Bias depth a bit if it's flush with underwater geometry
				if (colors[0] == 0 && colors[1] == 0 && colors[2] == 0)
					colors[0] = colors[1] = colors[2] = 1 << 16;
				putTriangle(vb, tb, tile, i * 3, baseX, baseZ, colors, materials, overlayEdges, terrainData, heightOffsets, 0, 1, normals);
			}
		}
		writeCache.release();
	}

	/**
	 * Draw the holes in a recreated floor black, like SceneUploader.uploadZoneGapFillers
	 */
	private void uploadGapFillers(
		SceneUploader uploader,
		ZoneSceneContext ctx,
		List<FloorTile> gapFillers,
		int basex,
		int basez,
		GpuIntBuffer vertexBuffer,
		GpuIntBuffer textureBuffer
	) {
		assert uploader.writeCache != null;
		var writeCache = uploader.writeCache;
		writeCache.setOutputBuffers(vertexBuffer, vertexBuffer, textureBuffer);
		final var vb = writeCache.getVertexBuffer();
		final var tb = writeCache.getTextureBuffer();

		var black = materialManager.getMaterial("BLACK");
		Material[] materials = { black, black, black };
		boolean[] overlayEdges = new boolean[3];
		int[] colors = new int[3];
		int[] terrainData = new int[3];
		int[] heightOffsets = new int[3];
		short[][] normals = { UP_NORMAL, UP_NORMAL, UP_NORMAL };
		for (var tile : gapFillers) {
			int baseX = (tile.tileExX - ctx.sceneOffset) * LOCAL_TILE_SIZE - basex;
			int baseZ = (tile.tileExY - ctx.sceneOffset) * LOCAL_TILE_SIZE - basez;
			Arrays.fill(terrainData, HDUtils.packTerrainData(true, 0, WaterType.NONE, tile.plane));
			for (int a = 0; a < tile.vertices.length; a += 9)
				putTriangle(vb, tb, tile, a, baseX, baseZ, colors, materials, overlayEdges, terrainData, heightOffsets, 0, 1, normals);
		}
		writeCache.release();
	}

	/**
	 * Write one of a floor tile's triangles, with its textures lined up with where the tile really is, the same way they
	 * are on real shaped tiles
	 */
	private static void putTriangle(
		VertexWriteCache vb,
		VertexWriteCache tb,
		FloorTile tile,
		int vertexOffset,
		int baseX,
		int baseZ,
		int[] colors,
		Material[] materials,
		boolean[] overlayEdges,
		int[] terrainData,
		int[] heightOffsets,
		int uvOrientation,
		float uvScale,
		short[][] normals
	) {
		int[] v = tile.vertices;
		int[] wp = tile.worldPos;
		float uvcos = -uvScale, uvsin = 0;
		if (uvOrientation % 2048 != 0) {
			float rad = -uvOrientation * JAU_TO_RAD;
			uvcos = cos(rad) * -uvScale;
			uvsin = sin(rad) * -uvScale;
		}
		float uvx = wp == null ? 0 : fract(wp[0] * uvcos - wp[1] * uvsin);
		float uvy = wp == null ? 0 : fract(wp[0] * uvsin + wp[1] * uvcos);

		int faceIdx = tb.putFace(
			colors[0], colors[1], colors[2],
			materials[0].packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, overlayEdges[0]),
			materials[1].packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, overlayEdges[1]),
			materials[2].packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, overlayEdges[2]),
			terrainData[0], terrainData[1], terrainData[2]
		);
		for (int k = 0; k < 3; k++) {
			int vx = v[vertexOffset + k * 3], vy = v[vertexOffset + k * 3 + 1] + heightOffsets[k], vz = v[vertexOffset + k * 3 + 2];
			float dx = (float) vx / LOCAL_TILE_SIZE;
			float dz = (float) vz / LOCAL_TILE_SIZE;
			vb.putStaticVertex(
				baseX + vx, vy, baseZ + vz,
				uvx + dx * uvcos - dz * uvsin, uvy + dx * uvsin + dz * uvcos, 0,
				normals[k][0], normals[k][1], normals[k][2],
				faceIdx, false
			);
		}
	}

	/**
	 * Resolve the whole floor's tile overrides, for blending its tiles into each other with
	 */
	private void resolveFloorOverrides(Map<FloorTile, TileOverride[]> overrides, Set<FloorTile> overlayMainTiles) {
		for (var tile : floor) {
			if (tile.worldPos == null)
				continue;
			var tileOverrides = new TileOverride[3];
			if (resolveOverrides(tile, tileOverrides))
				overlayMainTiles.add(tile);
			overrides.put(tile, tileOverrides);
		}
	}

	/**
	 * A captured floor tile's main, underlay & overlay tile overrides, from where the tile really is, returning whether the
	 * main one was found by the tile's overlay. That's only kept on the override until it's next looked up, so it's read here.
	 */
	private boolean resolveOverrides(FloorTile tile, TileOverride[] overrides) {
		int[] wp = tile.worldPos;
		assert wp != null;
		int overlay = TileOverride.OVERLAY_FLAG | tile.overlayId;
		var vars = getTileColorVariables(tile);
		overrides[0] = resolveReplacements(tileOverrideManager.getOverrideBeforeReplacements(wp, overlay, tile.underlayId), vars);
		boolean mainIsOverlay = overrides[0].queriedAsOverlay;
		overrides[1] = resolveReplacements(tileOverrideManager.getOverrideBeforeReplacements(wp, tile.underlayId), vars);
		overrides[2] = resolveReplacements(tileOverrideManager.getOverrideBeforeReplacements(wp, overlay), vars);
		return mainIsOverlay;
	}

	/**
	 * The recreated floor tile an object stands on, out of those recreated on its tile: the one nearest its height
	 */
	@Nullable
	private static FloorTile findFloorTile(@Nullable List<FloorTile> candidates, int objectHeight) {
		if (candidates == null)
			return null;
		FloorTile nearest = null;
		int nearestDistance = Integer.MAX_VALUE;
		for (var tile : candidates) {
			int sum = 0;
			for (int i = 1; i < tile.vertices.length; i += 3)
				sum += tile.vertices[i];
			int distance = Math.abs(sum / (tile.vertices.length / 3) - objectHeight);
			if (distance < nearestDistance) {
				nearest = tile;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	/**
	 * The real tile, but with the recreated floor's colours, for objects to inherit them like SceneUploader does. Painted
	 * tiles keep their corner colours, while shaped tiles become a tile of two triangles, shaped so the first is underlay
	 * and the second overlay, which is all 117 HD picks a colour by.
	 */
	private static Tile standInTile(Tile real, FloorTile floor) {
		var v = floor.vertices;
		boolean painted = RecreatedFloorBlending.isPainted(floor);
		int underlay = -1, overlay = -1, underlayTexture = -1, overlayTexture = -1;
		int sw = floor.colors[0], se = sw, ne = sw, nw = sw;
		for (int corner = 0; corner < floor.colors.length; corner++) {
			int triangle = corner / 3;
			int type = floor.overrideTypes == null ? 0 : floor.overrideTypes[triangle];
			int texture = floor.textures == null ? -1 : floor.textures[triangle];
			int color = floor.colors[corner];
			int x = v[corner * 3], z = v[corner * 3 + 2];
			if (type == 0) {
				underlayTexture = texture;
				if (x == 0 && z == 0)
					sw = color;
				else if (x != 0 && z == 0)
					se = color;
				else if (x != 0)
					ne = color;
				else
					nw = color;
			} else if (type == 2) {
				if (overlay == -1) {
					overlay = color;
					overlayTexture = texture;
				}
			} else if (underlay == -1) {
				underlay = color;
				underlayTexture = texture;
			}
		}

		Object standIn;
		if (painted) {
			int texture = underlayTexture;
			int[] corners = { sw, se, ne, nw };
			standIn = Reflection.stub(SceneTilePaint.class, name -> {
				switch (name) {
					case "getTexture":
						return texture;
					case "getRBG":
						return 1;
					case "getSwColor":
						return corners[0];
					case "getSeColor":
						return corners[1];
					case "getNeColor":
						return corners[2];
					case "getNwColor":
						return corners[3];
				}
				return null;
			});
		} else {
			int[] colors = { underlay != -1 ? underlay : overlay, overlay != -1 ? overlay : underlay };
			int[] textures = underlayTexture == -1 && overlayTexture == -1 ? null : new int[] { underlayTexture, overlayTexture };
			int overlayId = overlay == -1 ? 0 : floor.overlayId;
			standIn = Reflection.stub(SceneTileModel.class, name -> {
				switch (name) {
					// ProceduralGenerator.TILE_OVERLAY_TRIS lists this shape as { underlay, overlay }
					case "getShape":
						return 2;
					case "getTriangleColorA":
					case "getTriangleColorB":
					case "getTriangleColorC":
						return colors;
					case "getTriangleTextureId":
						return textures;
					case "getModelOverlay":
						return overlayId;
					case "getModelUnderlay":
						return floor.underlayId;
				}
				return null;
			});
		}

		boolean isPaint = painted;
		return Reflection.wrap(Tile.class, real, name -> {
			switch (name) {
				case "getSceneTilePaint":
					return isPaint ? standIn : Reflection.NULL;
				case "getSceneTileModel":
					return isPaint ? Reflection.NULL : standIn;
			}
			return null;
		});
	}


	private static int packTile(int level, int tileExX, int tileExY) {
		return level << 16 | tileExX << 8 | tileExY;
	}

	private static TileOverride resolveReplacements(TileOverride override, VariableSupplier vars) {
		return override.isConstant() ? override : override.resolveReplacements(vars);
	}

	/**
	 * The h, s & l variables tile override replacements pick by, from the captured tile's colour like TileOverrideVariables
	 * gets them from a real tile: its south-western corner, preferring the underlay
	 */
	private static VariableSupplier getTileColorVariables(FloorTile tile) {
		int color = tile.colors[0];
		var v = tile.vertices;
		for (int corner = 0; corner < tile.colors.length; corner++) {
			int triangle = corner / 3;
			boolean overlay = tile.overrideTypes != null && tile.overrideTypes[triangle] == 2;
			if (!overlay && v[corner * 3] == 0 && v[corner * 3 + 2] == 0) {
				color = tile.colors[corner];
				break;
			}
		}
		int[] hsl = new int[3];
		ColorUtils.unpackRawHsl(hsl, color);
		return name -> {
			switch (name) {
				case "h":
					return hsl[0];
				case "s":
					return hsl[1];
				case "l":
					return hsl[2];
			}
			throw new IllegalArgumentException("Undefined variable '" + name + "'");
		};
	}

	@Nullable
	private Zone getDrawableZone(Scene scene, int zx, int zz) {
		if (contents.isEmpty() || scene == null || scene.getWorldViewId() != WorldView.TOPLEVEL)
			return null;
		if (zx < 0 || zz < 0 || zx >= NUM_ZONES || zz >= NUM_ZONES)
			return null;
		Zone zone = zones[zx][zz];
		return zone != null && zone.initialized && plugin.renderer == uploadedFor ? zone : null;
	}

	/**
	 * Draw the recreated zone in the same place, right after 117 HD has drawn the real one
	 */
	/**
	 * Switch off the real zone's gap fillers while 117 HD draws it, if they're being hidden, since they're drawn whatever
	 * render callbacks say. {@link #restoreRealGapFillers} must be called once it's drawn.
	 */
	public void beforeDrawZoneOpaque(Scene scene, int zx, int zz) {
		if (!hidingRealGapFillers || scene == null || scene.getWorldViewId() != WorldView.TOPLEVEL)
			return;
		var root = sceneManager.getRoot();
		if (root == null || zx < 0 || zz < 0 || zx >= root.zones.length || zz >= root.zones[zx].length)
			return;
		Zone real = root.zones[zx][zz];
		if (real != null && real.hasGapFiller) {
			real.hasGapFiller = false;
			realZoneWithoutGapFillers = real;
		}
	}

	public void restoreRealGapFillers() {
		if (realZoneWithoutGapFillers != null)
			realZoneWithoutGapFillers.hasGapFiller = true;
		realZoneWithoutGapFillers = null;
	}

	/**
	 * Draw the recreated zone in the same place, right after 117 HD has drawn the real one
	 */
	public void afterDrawZoneOpaque(Scene scene, int zx, int zz) throws IllegalAccessException {
		Zone zone = getDrawableZone(scene, zx, zz);
		var root = sceneManager.getRoot();
		if (zone == null || root == null)
			return;
		var renderer = (ZoneRenderer) plugin.renderer;

		// Culled along with the real zone, which 117 HD has just checked
		Zone real = root.zones[zx][zz];
		zone.inSceneFrustum = real.inSceneFrustum;
		zone.inShadowFrustum = real.inShadowFrustum;

		if (zone.inSceneFrustum) {
			renderer.sceneCmd.Disable(GL_BLEND);
			if (root.minLevel == 0)
				zone.renderOpaqueLevel(renderer.sceneCmd, Zone.LEVEL_TERRAIN);
			zone.renderOpaque(renderer.sceneCmd, root, false);

			if (root.minLevel == 0 && zone.hasGapFiller && root.sceneContext.fillGaps)
				zone.renderOpaqueLevel(renderer.gapFillerCmd, Zone.LEVEL_GAP_FILLER);
		}

		if (castsShadows(renderer) && zone.inShadowFrustum) {
			renderer.directionalCmd.SetShader((rs117.hd.opengl.shader.ShaderProgram) FAST_SHADOW_PROGRAM.get(renderer));
			zone.renderOpaque(renderer.directionalCmd, root, SHOULD_DRAW_ROOF_SHADOWS.getBoolean(renderer));
		}
	}

	/**
	 * Sort the recreated zones' transparent faces for this frame, like WorldViewContext.sortStaticAlphaModels, right after
	 * 117 HD has queued sorting its own. Zones are culled by the real zones' visibility from the previous frame.
	 */
	public void afterPreSceneDraw(Scene scene) {
		if (contents.isEmpty() || scene == null || scene.getWorldViewId() != WorldView.TOPLEVEL || plugin.renderer != uploadedFor)
			return;
		var root = sceneManager.getRoot();
		if (root == null || root.sceneContext == null || !(plugin.renderer instanceof ZoneRenderer))
			return;
		var camera = ((ZoneRenderer) plugin.renderer).sceneCamera;
		int offset = root.sceneContext.sceneOffset >> 3;
		int camPosX = (int) camera.getPositionX();
		int camPosZ = (int) camera.getPositionZ();
		for (int zx = 0; zx < NUM_ZONES; zx++) {
			for (int zz = 0; zz < NUM_ZONES; zz++) {
				Zone zone = zones[zx][zz];
				if (zone == null || !zone.initialized || zone.alphaModels.isEmpty() || !zone.inSceneFrustum)
					continue;
				int dx = camPosX - ((zx - offset) << 10);
				int dz = camPosZ - ((zz - offset) << 10);
				zone.dist = dx * dx + dz * dz;
				zone.alphaStaticModelSort(camera);
			}
		}
	}

	/**
	 * Draw the recreated zone's transparent models, right after 117 HD has drawn the real zone's
	 */
	public void afterDrawZoneAlpha(Scene scene, int level, int zx, int zz) throws IllegalAccessException {
		Zone zone = getDrawableZone(scene, zx, zz);
		var root = sceneManager.getRoot();
		if (zone == null || root == null)
			return;
		var renderer = (ZoneRenderer) plugin.renderer;
		int offset = root.sceneContext.sceneOffset >> 3;
		var cmd = renderer.sceneCmd;
		cmd.Enable(GL_BLEND);

		// The water's surface, drawn with the water shader like ZoneRenderer.drawZoneAlpha draws real water
		if (zone.hasWater && zone.inSceneFrustum && root.minLevel == 0 && level == 0) {
			cmd.SetShader((rs117.hd.opengl.shader.ShaderProgram) WATER_PROGRAM.get(renderer));
			zone.renderOpaqueLevel(cmd, Zone.LEVEL_WATER_SURFACE);
			cmd.SetShader((rs117.hd.opengl.shader.ShaderProgram) SCENE_PROGRAM.get(renderer));
		}

		if (zone.alphaModels.isEmpty())
			return;

		if (castsShadows(renderer) && zone.inShadowFrustum) {
			var program = plugin.configShadowMode == ShadowMode.DETAILED ? DETAILED_SHADOW_PROGRAM : FAST_SHADOW_PROGRAM;
			renderer.directionalCmd.SetShader((rs117.hd.opengl.shader.ShaderProgram) program.get(renderer));
			zone.renderAlpha(renderer.directionalCmd, zx - offset, zz - offset, level, root, true, SHOULD_DRAW_ROOF_SHADOWS.getBoolean(renderer));
		}

		// Order the zone's models back to front, as 117 HD does for its own zones
		if (level == 0 && zone.inSceneFrustum)
			zone.alphaSort(zx - offset, zz - offset, renderer.sceneCamera);

		if (zone.inSceneFrustum) {
			// Like 117 HD's own static alpha: first blend the colors without writing depth, then write depth without color
			cmd.DepthMask(false);
			cmd.ColorMask(true, true, true, true);
			zone.renderAlpha(cmd, zx - offset, zz - offset, level, root, false, false);
			cmd.DepthMask(true);
			cmd.ColorMask(false, false, false, false);
			zone.renderAlpha(cmd, zx - offset, zz - offset, level, root, true, false);
			cmd.ColorMask(true, true, true, true);
		}
	}

	public void afterDrawPass(Scene scene, int pass) {
		if (pass != DrawCallbacks.PASS_ALPHA || contents.isEmpty() || scene == null || scene.getWorldViewId() != WorldView.TOPLEVEL)
			return;
		for (var column : zones)
			for (var zone : column)
				if (zone != null && zone.initialized)
					zone.postAlphaPass();
	}

	private boolean castsShadows(ZoneRenderer renderer) throws IllegalAccessException {
		return ((SkyRenderer) SKY_RENDERER.get(renderer)).castsShadows;
	}

	/**
	 * Drop a zone whose buffers went with the renderer that made them, without deleting anything that may now be someone else's
	 */
	private static void forget(Zone zone) {
		zone.glVao = 0;
		zone.glVaoA = 0;
		zone.vboO = zone.vboA = zone.vboM = null;
		zone.tboF = null;
		zone.initialized = false;
	}

	private static int packZone(int zx, int zz) {
		return zx << 16 | zz;
	}
}
