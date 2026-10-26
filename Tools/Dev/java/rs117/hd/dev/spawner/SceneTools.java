package rs117.hd.dev.spawner;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.RenderCallback;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import rs117.hd.HdPlugin;
import rs117.hd.dev.HdInternals;
import rs117.hd.dev.TestObjectLights;
import rs117.hd.dev.TestObjectRenderer;
import rs117.hd.renderer.zone.RecreatedSceneRenderer;
import rs117.hd.renderer.zone.RecreatedSceneRenderer.FloorTile;
import rs117.hd.renderer.zone.RecreatedSceneRenderer.StaticObject;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.ModelOverrideManager;
import rs117.hd.scene.areas.Area;
import rs117.hd.scene.environments.Environment;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.ModelHash;

import static rs117.hd.utils.MathUtils.*;

/**
 * Saves the objects, NPCs and ground the game draws around the player to a file, and recreates saved captures in the
 * player's region, for tuning a scene's textures & lights somewhere else. Driven by the Scenes tab of the spawner sidebar.
 */
@Slf4j
@Singleton
public class SceneTools {
	// Spread recreating a scene capture's objects over ticks, since a whole scene can hold thousands
	private static final long SCENE_RECREATION_NANOS_PER_TICK = 4_000_000;

	@Inject
	@Named("developerMode")
	private boolean developerMode;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private Gson gson;

	@Inject
	private EventBus eventBus;

	@Inject
	private RenderCallbackManager renderCallbackManager;

	@Inject
	private HdPlugin plugin;

	@Inject
	private EnvironmentManager environmentManager;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	@Inject
	private HdInternals hdInternals;

	@Inject
	private TestObjectRenderer testObjectRenderer;

	@Inject
	private TestObjectLights testObjectLights;

	@Inject
	private RecreatedSceneRenderer recreatedSceneRenderer;

	@Inject
	private Provider<SpawnerTools> spawnerTools;

	// Animated objects recreated from a scene capture, which need RuneLiteObjects. Everything else is uploaded like static
	// scenery by the RecreatedSceneRenderer.
	private final List<RuneLiteObject> recreatedSceneObjects = new ArrayList<>();
	private List<StaticObject> recreatedStaticObjects = List.of();
	private List<FloorTile> recreatedFloor = List.of();
	// Real tiles under the recreated floor, by level & extended scene position, which aren't drawn while it's there
	private Set<Integer> floorTileKeys = Set.of();
	@Nullable
	private SceneRecreation sceneRecreation;
	private boolean onlyDrawingTestObjects;

	// The environments.json environment a recreated scene is lit by, and the area it had before being made to cover everything
	@Nullable
	private String overrideEnvironmentName;
	@Nullable
	private Environment overriddenEnvironment;
	private Area overriddenEnvironmentArea;

	// Stop drawing everything but test objects, the same way the Blindfold plugin hides things. It only affects drawing,
	// leaving clickboxes, menus and everything else alone.
	private final RenderCallback onlyDrawTestObjects = new RenderCallback() {
		@Override
		public boolean drawTile(Scene scene, Tile tile) {
			return false;
		}

		@Override
		public boolean drawObject(Scene scene, TileObject object) {
			if (!(object instanceof GameObject))
				return false;
			// Keep the local player, but nobody else, like the Blindfold plugin does
			var renderable = ((GameObject) object).getRenderable();
			if (renderable != null && renderable == client.getLocalPlayer())
				return true;
			// Test objects are drawn wrapped in a temporary game object, which the renderer matches by its position
			return testObjectRenderer.getTestObjectId(renderable, object.getX(), object.getY()) != -1;
		}
	};

	// Leave out the real tiles a recreated floor is drawn in place of
	private final RenderCallback hideFloorTiles = new RenderCallback() {
		@Override
		public boolean drawTile(Scene scene, Tile tile) {
			if (floorTileKeys.isEmpty() || scene.getWorldViewId() != WorldView.TOPLEVEL)
				return true;
			var sceneLocation = tile.getSceneLocation();
			return !floorTileKeys.contains(packTile(
				tile.getRenderLevel(),
				sceneLocation.getX() + HDUtils.EXTENDED_SCENE_OFFSET,
				sceneLocation.getY() + HDUtils.EXTENDED_SCENE_OFFSET
			));
		}
	};

	// Leaves what the game and 117 HD aren't drawing out of scene captures, checked the same way 117 HD's SceneUploader does
	private final SceneCapture.Visibility sceneVisibility = new SceneCapture.Visibility() {
		private final int[] worldPos = new int[3];

		@Nullable
		@Override
		public Boolean isLevelDrawn(int level, int roofId) {
			return recreatedSceneRenderer.isLevelDrawn(level, roofId);
		}

		@Override
		public boolean isHiddenByArea(Tile tile) {
			var ctx = plugin.getSceneContext();
			if (ctx == null || ctx.currentArea == null)
				return false;
			var sceneLocation = tile.getSceneLocation();
			ctx.sceneToWorld(sceneLocation.getX(), sceneLocation.getY(), tile.getPlane(), worldPos);
			return !ctx.currentArea.containsPoint(worldPos);
		}

		@Override
		public boolean isHiddenByOverride(TileObject object, int type) {
			var ctx = plugin.getSceneContext();
			if (ctx == null)
				return false;
			var location = object.getLocalLocation();
			ctx.localToWorld(location.getX(), location.getY(), object.getPlane(), worldPos);
			return modelOverrideManager.getOverride(ModelHash.packUuid(type, object.getId()), worldPos).hide;
		}

		@Override
		public void getWorldPosition(Tile tile, int level, int[] result) {
			var ctx = plugin.getSceneContext();
			var sceneLocation = tile.getSceneLocation();
			if (ctx == null) {
				var world = WorldPoint.fromScene(client.getTopLevelWorldView(), sceneLocation.getX(), sceneLocation.getY(), level);
				result[0] = world.getX();
				result[1] = world.getY();
				result[2] = world.getPlane();
				return;
			}
			ctx.sceneToWorld(sceneLocation.getX(), sceneLocation.getY(), level, result);
		}
	};

	public void startUp() {
		eventBus.register(this);
	}

	/**
	 * Must be called on the client thread
	 */
	public void shutDown() {
		eventBus.unregister(this);
		// Stop hiding the scene first, since the render callbacks would otherwise keep hiding it
		setOnlyDrawingTestObjects(false);
		removeRecreatedScene();
	}

	public boolean hasRecreatedScene() {
		return sceneRecreation != null || !recreatedSceneObjects.isEmpty() || recreatedSceneRenderer.hasContent();
	}

	@Subscribe
	public void onClientTick(ClientTick clientTick) {
		updateSceneRecreation();
		recreatedSceneRenderer.update();
		updateEnvironmentOverride();
		if (onlyDrawingTestObjects)
			hideUndrawnObjectLights();
	}

	/**
	 * Capture the objects, NPCs and ground the game draws in an area around the player, and save them to a file.
	 * Can be called from any thread.
	 */
	public void captureScene(String name, SceneCapture.Area area) {
		clientThread.invoke(() -> {
			if (!developerMode)
				return;
			String captureName = name.isEmpty() ? SceneCapture.getDefaultName(getPlayerEnvironmentName()) : name;
			var capture = SceneCapture.capture(client, captureName, area, sceneVisibility);
			if (capture == null) {
				postMessage("No local player to capture the scene around");
				return;
			}
			var environment = environmentManager.getTargetEnvironment();
			if (environment != Environment.NONE)
				capture.environment = environment.toString();
			executor.execute(() -> {
				try {
					String fileName = SceneCapture.save(gson, capture);
					postMessage("Captured " + capture.objects.size() + " objects, " + capture.npcs.size() + " NPCs and " +
								capture.tiles.size() + " tiles to " + SceneCapture.CAPTURES_PATH.resolve(fileName));
					var panel = spawnerTools.get().getPanel();
					if (panel != null)
						SwingUtilities.invokeLater(() -> panel.setSelectedSceneCapture(fileName));
				} catch (IOException ex) {
					log.error("Failed to save scene capture", ex);
					postMessage("Failed to save the scene capture: " + ex.getMessage());
				}
			});
		});
	}

	/**
	 * The name of the 117 HD environment the player is in, like the tile info overlay shows, or null if there's none
	 */
	@Nullable
	private String getPlayerEnvironmentName() {
		var ctx = plugin.getSceneContext();
		Player player = client.getLocalPlayer();
		if (ctx == null || player == null)
			return null;
		var location = player.getLocalLocation();
		int[] worldPos = ctx.localToWorld(location.getX(), location.getY(), client.getTopLevelWorldView().getPlane());
		for (var environment : ctx.environments)
			if (environment.area.containsPoint(worldPos))
				return environment.toString();
		return null;
	}

	/**
	 * Pass the file names of the saved scene captures to the callback on the Swing thread
	 */
	public void listSceneCaptures(Consumer<List<String>> callback) {
		if (!developerMode)
			return;
		executor.execute(() -> {
			List<String> fileNames;
			try {
				fileNames = SceneCapture.list();
			} catch (IOException ex) {
				log.error("Failed to list scene captures", ex);
				fileNames = List.of();
			}
			var result = fileNames;
			SwingUtilities.invokeLater(() -> callback.accept(result));
		});
	}

	/**
	 * Recreate a saved scene capture in the player's region, replacing any previously recreated one
	 */
	public void recreateSceneCapture(String fileName) {
		if (!developerMode)
			return;
		executor.execute(() -> {
			SceneCapture.Capture capture;
			try {
				capture = SceneCapture.load(gson, fileName);
			} catch (IOException | RuntimeException ex) {
				log.error("Failed to load scene capture {}", fileName, ex);
				postMessage("Failed to load the scene capture: " + ex.getMessage());
				return;
			}
			clientThread.invoke(() -> startSceneRecreation(capture));
		});
	}

	private void startSceneRecreation(SceneCapture.Capture capture) {
		assert client.isClientThread();
		if (!developerMode)
			return;
		Player player = client.getLocalPlayer();
		var worldView = client.getTopLevelWorldView();
		if (player == null || worldView == null) {
			postMessage("No local player to recreate the scene around");
			return;
		}
		removeRecreatedScene();
		// Light the recreated scene like where it was captured, until it's removed
		overrideEnvironmentName = capture.environment;

		// Work out where everything goes now, so moving while it spawns doesn't move the rest
		LocalPoint origin = player.getLocalLocation();
		int originPlane = worldView.getPlane();
		int groundHeight = Perspective.getTileHeight(client, origin, originPlane);
		var recreation = new SceneRecreation(capture.name == null ? "the scene capture" : capture.name);
		recreation.terrain = capture.terrain;
		recreation.capturedPlane = capture.capturedAt == null ? 0 : capture.capturedAt.getPlane();
		// Line everything up with the region grid like where it was captured
		var scene = worldView.getScene();
		int baseX = SceneCapture.getRegionCorner(scene.getBaseX(), origin.getX());
		int baseY = SceneCapture.getRegionCorner(scene.getBaseY(), origin.getY());
		for (var object : capture.objects) {
			if (object.kind == null) {
				recreation.skippedUnreadable++;
				continue;
			}
			for (var part : SceneCapture.getParts(object)) {
				int x = baseX + object.x + (part.offset ? object.xOffset : 0);
				int y = baseY + object.y + (part.offset ? object.yOffset : 0);
				// Static objects can go in the extended area 117 HD loads around the scene, unlike RuneLiteObjects
				var location = new LocalPoint(x, y, worldView);
				if (getSceneTile(location, originPlane) == null && !location.isInScene()) {
					recreation.skippedOutsideScene++;
					continue;
				}
				int orientation = mod((part.rotation & 3) * 512 + object.modelOrientation, 2048);
				// Everything goes on the player's plane, since the captured heights already keep objects on other planes
				// where they were, and the player's plane isn't hidden like the planes above it can be
				int height = groundHeight + object.height;
				recreation.pending.add(new PendingScenePart(object, part, location, originPlane, orientation, height));
			}
		}
		recreateFloor(capture, baseX, baseY, originPlane, groundHeight, recreation);
		recreateNpcs(capture, baseX, baseY, originPlane, groundHeight, recreation);

		// Big captures take a while, so start with what's around the player rather than the scene's edge
		recreation.pending.sort(Comparator.comparingLong(pending -> {
			long dx = pending.location.getX() - origin.getX();
			long dy = pending.location.getY() - origin.getY();
			return dx * dx + dy * dy;
		}));
		sceneRecreation = recreation;
		postMessage("Recreating " + recreation.pending.size() + " objects from " + recreation.name + "...");
	}

	private void updateSceneRecreation() {
		var recreation = sceneRecreation;
		if (recreation == null)
			return;

		long deadline = System.nanoTime() + SCENE_RECREATION_NANOS_PER_TICK;
		while (recreation.next < recreation.pending.size() && System.nanoTime() < deadline) {
			var pending = recreation.pending.get(recreation.next++);
			try {
				recreateObject(recreation, pending);
			} catch (RuntimeException ex) {
				log.warn("Unable to recreate object {}", pending.object.id, ex);
				recreation.skippedNoModel++;
			}
		}

		if (recreation.next < recreation.pending.size())
			return;
		finishSceneRecreation(recreation);
	}

	private void recreateObject(SceneRecreation recreation, PendingScenePart pending) {
		var spawner = spawnerTools.get();
		var captured = pending.object;

		// Use the model the client drew where the capture kept one, since it's already rotated and bent like the original
		if (captured.model != null) {
			if (getSceneTile(pending.location, pending.plane) == null) {
				recreation.skippedOutsideScene++;
				return;
			}
			recreation.staticObjects.add(new StaticObject(
				ModelHash.TYPE_OBJECT,
				captured.id,
				captured.model.toModel(),
				HDUtils.getModelPreOrientation(captured.shape | captured.rotation << 6),
				captured.modelOrientation,
				mod(1024 + pending.orientation, 2048),
				pending.plane,
				pending.location.getX(),
				pending.location.getY(),
				pending.height
			));
			return;
		}

		var def = spawner.readTestObjectDefinition(captured.id);
		if (def == null) {
			recreation.skippedUnreadable++;
			return;
		}
		int[] modelIds = SceneCapture.getModelIds(def, pending.part);
		if (modelIds == null) {
			recreation.skippedNoModel++;
			return;
		}
		int quarterTurns = pending.part.rotation & 3;
		Consumer<ModelData> contour = def.contouredGround < 0 || recreation.terrain == null ? null : rotated -> SceneCapture.contour(
			rotated,
			def.contouredGround,
			recreation.terrain,
			recreation.capturedPlane + captured.plane,
			captured.x + (pending.part.offset ? captured.xOffset : 0),
			captured.y + (pending.part.offset ? captured.yOffset : 0),
			captured.height
		);

		// Upload objects like static scenery where possible, so they're not redrawn every frame. Animations need
		// RuneLiteObjects though, as do objects without a tile to be uploaded with.
		Tile tile = def.animation == -1 ? getSceneTile(pending.location, pending.plane) : null;
		if (tile != null) {
			Model model = spawner.buildTestObjectModel(
				def,
				modelIds,
				def.mirrored != pending.part.isMirrored(),
				pending.part.isDiagonal(),
				// Kept rotated like the client's, since static scenery isn't rotated again when drawn
				modelData -> SceneCapture.finishModel(modelData, def, quarterTurns, contour, 0, true),
				false
			);
			if (model == null) {
				recreation.skippedNoModel++;
				return;
			}
			recreation.staticObjects.add(new StaticObject(
				ModelHash.TYPE_OBJECT,
				captured.id,
				model,
				HDUtils.getModelPreOrientation(captured.shape | captured.rotation << 6),
				captured.modelOrientation,
				mod(1024 + pending.orientation, 2048),
				pending.plane,
				pending.location.getX(),
				pending.location.getY(),
				pending.height
			));
			return;
		}

		if (!pending.location.isInScene()) {
			recreation.skippedOutsideScene++;
			return;
		}

		// The renderer recognises test objects by their exact position, so move each off the spot where the real object
		// it stands in for would be, and off other test objects, such as parts of the same wall
		var location = pending.location;
		int nudge = 0;
		do {
			location = location.dx(1);
			nudge++;
		} while (spawner.isTestObjectAt(location));

		int nudgeBack = nudge;
		Model model = spawner.buildTestObjectModel(
			def,
			modelIds,
			def.mirrored != pending.part.isMirrored(),
			pending.part.isDiagonal(),
			// Moving the model back by the nudge lets pieces nudged by different amounts still meet without cracks
			modelData -> SceneCapture.finishModel(modelData, def, quarterTurns, contour, nudgeBack, false),
			false
		);
		if (model == null) {
			recreation.skippedNoModel++;
			return;
		}
		var object = spawner.placeTestObject(captured.id, def, model, location, pending.plane, pending.orientation, pending.height);
		if (object != null)
			recreatedSceneObjects.add(object);
	}

	private void finishSceneRecreation(SceneRecreation recreation) {
		sceneRecreation = null;
		recreatedStaticObjects = recreation.staticObjects;
		recreatedFloor = recreation.floor;
		recreatedSceneRenderer.set(recreatedStaticObjects, recreatedFloor);
		testObjectLights.setStaticObjects(recreatedStaticObjects);
		if (!recreatedFloor.isEmpty()) {
			// 117 HD only asks render callbacks about tiles while uploading the scene, so upload it again without them
			Set<Integer> keys = new HashSet<>();
			for (var tile : recreatedFloor)
				keys.add(packTile(tile.plane, tile.tileExX, tile.tileExY));
			floorTileKeys = keys;
			renderCallbackManager.register(hideFloorTiles);
			reloadScene();
		}

		int objects = recreatedSceneObjects.size() + recreatedStaticObjects.size() - recreation.recreatedNpcs;
		String message = "Recreated " + objects + " objects" +
						 (recreation.recreatedNpcs == 0 ? "" : ", " + recreation.recreatedNpcs + " NPCs") +
						 (recreatedFloor.isEmpty() ? "" : " and " + recreatedFloor.size() + " floor tiles") + " from " + recreation.name;
		if (recreation.skippedOutsideScene > 0)
			message += ". Skipped " + recreation.skippedOutsideScene + " outside the loaded scene";
		if (recreation.skippedUnreadable > 0)
			message += ". Skipped " + recreation.skippedUnreadable + " whose definition couldn't be read";
		if (recreation.skippedNoModel > 0)
			message += ". Skipped " + recreation.skippedNoModel + " without a model for their shape";
		if (recreation.skippedFloorTiles > 0)
			message += ". Skipped " + recreation.skippedFloorTiles + " floor tiles where there's no tile to replace";
		postMessage(message);
	}

	/**
	 * Upload 117 HD's copy of the scene again, unless the renderer has already been destroyed, such as while 117 HD stops
	 */
	private void reloadScene() {
		var renderer = plugin.renderer;
		if (renderer != null)
			renderer.reloadScene();
	}

	/**
	 * The tile at a local point in the top-level scene, including the extended area around it, or null if there's none
	 */
	@Nullable
	private Tile getSceneTile(LocalPoint location, int plane) {
		return getExtendedTile(
			plane,
			(location.getX() >> Perspective.LOCAL_COORD_BITS) + HDUtils.EXTENDED_SCENE_OFFSET,
			(location.getY() >> Perspective.LOCAL_COORD_BITS) + HDUtils.EXTENDED_SCENE_OFFSET
		);
	}

	@Nullable
	private Tile getExtendedTile(int plane, int tileExX, int tileExY) {
		var tiles = client.getTopLevelWorldView().getScene().getExtendedTiles();
		if (plane < 0 || plane >= tiles.length || tileExX < 0 || tileExY < 0 ||
			tileExX >= tiles[plane].length || tileExY >= tiles[plane][tileExX].length)
			return null;
		return tiles[plane][tileExX][tileExY];
	}

	/**
	 * Lay a capture's tiles out as a floor in the player's region, drawn in place of the real tiles. Like objects, every
	 * plane goes on the player's.
	 */
	private void recreateFloor(
		SceneCapture.Capture capture,
		int baseX,
		int baseY,
		int originPlane,
		int groundHeight,
		SceneRecreation recreation
	) {
		for (var captured : capture.tiles) {
			int tileExX = (baseX >> Perspective.LOCAL_COORD_BITS) + captured.x + HDUtils.EXTENDED_SCENE_OFFSET;
			int tileExY = (baseY >> Perspective.LOCAL_COORD_BITS) + captured.y + HDUtils.EXTENDED_SCENE_OFFSET;
			Tile tile = getExtendedTile(originPlane, tileExX, tileExY);
			var floorTile = tile == null ? null : createFloorTile(captured, tile.getRenderLevel(), tileExX, tileExY, groundHeight);
			if (floorTile == null) {
				recreation.skippedFloorTiles++;
				continue;
			}
			recreation.floor.add(floorTile);
		}
	}

	/**
	 * Place frozen copies of a capture's NPCs, built from their definitions' models, colours & scale, and uploaded like
	 * static scenery. They're never animated and do nothing, since they only show what was there.
	 */
	private void recreateNpcs(
		SceneCapture.Capture capture,
		int baseX,
		int baseY,
		int originPlane,
		int groundHeight,
		SceneRecreation recreation
	) {
		var worldView = client.getTopLevelWorldView();
		for (var captured : capture.npcs) {
			var location = new LocalPoint(baseX + captured.x, baseY + captured.y, worldView);
			Tile tile = getSceneTile(location, originPlane);
			if (tile == null) {
				recreation.skippedOutsideScene++;
				continue;
			}
			Model model = buildNpcModel(captured.id);
			if (model == null) {
				recreation.skippedNoModel++;
				continue;
			}
			recreation.staticObjects.add(new StaticObject(
				ModelHash.TYPE_NPC,
				captured.id,
				model,
				0,
				captured.orientation,
				captured.orientation,
				originPlane,
				location.getX(),
				location.getY(),
				groundHeight + captured.height
			));
			recreation.recreatedNpcs++;
		}
	}

	@Nullable
	private Model buildNpcModel(int npcId) {
		var composition = client.getNpcDefinition(npcId);
		int[] modelIds = composition == null ? null : composition.getModels();
		if (modelIds == null || modelIds.length == 0)
			return null;
		ModelData[] models = new ModelData[modelIds.length];
		for (int i = 0; i < models.length; i++) {
			models[i] = client.loadModelData(modelIds[i]);
			if (models[i] == null)
				return null;
		}

		ModelData modelData = models.length == 1 ? models[0] : client.mergeModels(models);
		short[] recolorFrom = composition.getColorToReplace();
		short[] recolorTo = composition.getColorToReplaceWith();
		if (recolorFrom != null && recolorTo != null) {
			modelData = modelData.cloneColors();
			for (int i = 0; i < min(recolorFrom.length, recolorTo.length); i++)
				modelData.recolor(recolorFrom[i], recolorTo[i]);
		}
		int width = composition.getWidthScale();
		int height = composition.getHeightScale();
		if (width != 128 || height != 128)
			modelData = modelData.cloneVertices().scale(width, height, width);
		return modelData.light(
			ModelData.DEFAULT_AMBIENT,
			ModelData.DEFAULT_CONTRAST,
			ModelData.DEFAULT_X,
			ModelData.DEFAULT_Y,
			ModelData.DEFAULT_Z
		);
	}

	/**
	 * A captured tile's triangles at the player's ground height, or null if the capture saved no shape for it
	 */
	@Nullable
	private static FloorTile createFloorTile(SceneCapture.CapturedTile captured, int plane, int tileExX, int tileExY, int groundHeight) {
		if (captured.vertices == null || captured.colors == null)
			return null;
		int[] vertices = captured.vertices.clone();
		for (int i = 1; i < vertices.length; i += 3)
			vertices[i] += groundHeight;
		return new FloorTile(
			plane,
			tileExX,
			tileExY,
			vertices,
			captured.colors,
			captured.worldPos,
			captured.underlayId,
			captured.overlayId,
			captured.textures,
			captured.overrideTypes
		);
	}

	/**
	 * Hide the tiles, objects, NPCs and other players, leaving only spawned & recreated test objects and the local player drawn.
	 * Can be called from any thread.
	 */
	public void setOnlyDrawTestObjects(boolean only) {
		clientThread.invoke(() -> {
			if (developerMode)
				setOnlyDrawingTestObjects(only);
		});
	}

	private void setOnlyDrawingTestObjects(boolean only) {
		if (only == onlyDrawingTestObjects)
			return;
		onlyDrawingTestObjects = only;
		if (only) {
			renderCallbackManager.register(onlyDrawTestObjects);
		} else {
			renderCallbackManager.unregister(onlyDrawTestObjects);
			// Bring back the lights of the objects which were hidden
			if (plugin.getSceneContext() != null)
				hdInternals.reloadLights();
		}
		// 117 HD only asks render callbacks about static tiles & objects while uploading the scene, which it can redo
		// without the game reload Blindfold uses, so test objects stay
		reloadScene();
	}

	/**
	 * Fade out the lights of objects which aren't being drawn, which 117 HD would otherwise keep lit
	 */
	private void hideUndrawnObjectLights() {
		if (plugin.getSceneContext() == null)
			return;
		var scene = client.getTopLevelWorldView().getScene();
		for (var light : hdInternals.getLights())
			if (light.tileObject != null && !light.markedForRemoval && !renderCallbackManager.drawObject(scene, light.tileObject))
				light.markedForRemoval = true;
	}

	/**
	 * Light the scene with the recreated capture's environment wherever the camera is, picking it up again whenever
	 * environments.json is reloaded so edits show up live
	 */
	private void updateEnvironmentOverride() {
		var ctx = plugin.getSceneContext();
		if (ctx == null)
			return;
		var environment = overrideEnvironmentName == null ? null : hdInternals.findEnvironment(overrideEnvironmentName);
		if (environment != overriddenEnvironment) {
			restoreEnvironment();
			if (environment != null) {
				overriddenEnvironment = environment;
				overriddenEnvironmentArea = environment.area;
				// Cover everywhere, keeping the area's name, which can also be the environment's
				environment.area = new Area(
					environment.area.name,
					Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE
				);
			}
		}
		// 117 HD picks the first of the scene's environments which contains the camera
		if (environment != null && (ctx.environments.isEmpty() || ctx.environments.get(0) != environment)) {
			ctx.environments.remove(environment);
			ctx.environments.add(0, environment);
		}
	}

	private void restoreEnvironment() {
		if (overriddenEnvironment == null)
			return;
		overriddenEnvironment.area = overriddenEnvironmentArea;
		overriddenEnvironment = null;
		var ctx = plugin.getSceneContext();
		if (ctx != null)
			environmentManager.loadSceneEnvironments(ctx);
	}

	public void removeRecreatedSceneAndNotify() {
		clientThread.invoke(() -> {
			removeRecreatedScene();
			postMessage("Removed the recreated scene");
		});
	}

	/**
	 * Must be called on the client thread
	 */
	void removeRecreatedScene() {
		sceneRecreation = null;
		for (var object : recreatedSceneObjects) {
			object.setActive(false);
			testObjectRenderer.remove(object);
		}
		recreatedSceneObjects.clear();

		for (var object : recreatedStaticObjects)
			object.removed = true;
		recreatedStaticObjects = List.of();
		recreatedFloor = List.of();
		testObjectLights.setStaticObjects(List.of());
		recreatedSceneRenderer.clear();
		if (!floorTileKeys.isEmpty()) {
			floorTileKeys = Set.of();
			renderCallbackManager.unregister(hideFloorTiles);
			reloadScene();
		}

		overrideEnvironmentName = null;
		restoreEnvironment();
	}

	private void postMessage(String message) {
		spawnerTools.get().postMessage(message);
	}

	private static int packTile(int level, int tileExX, int tileExY) {
		return level << 16 | tileExX << 8 | tileExY;
	}

	@RequiredArgsConstructor
	private static class SceneRecreation {
		final String name;
		final List<PendingScenePart> pending = new ArrayList<>();
		final List<StaticObject> staticObjects = new ArrayList<>();
		final List<FloorTile> floor = new ArrayList<>();
		int next;
		int skippedOutsideScene;
		int skippedUnreadable;
		int skippedNoModel;
		int skippedFloorTiles;
		int recreatedNpcs;
		@Nullable
		SceneCapture.Terrain terrain;
		int capturedPlane;
	}

	@RequiredArgsConstructor
	private static class PendingScenePart {
		final SceneCapture.CapturedObject object;
		final SceneCapture.Part part;
		final LocalPoint location;
		final int plane;
		final int orientation;
		final int height;
	}
}
