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

/** Captures scenes to files and recreates them in the player's region, for tuning textures & lights elsewhere */
@Slf4j
@Singleton
public class SceneTools {
	// A whole scene can hold thousands of objects
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

	// Animated objects need RuneLiteObjects, while everything else is uploaded by the RecreatedSceneRenderer
	private final List<RuneLiteObject> recreatedSceneObjects = new ArrayList<>();
	private List<StaticObject> recreatedStaticObjects = List.of();
	private List<FloorTile> recreatedFloor = List.of();
	// Real tiles under the recreated floor, which aren't drawn
	private Set<Integer> floorTileKeys = Set.of();
	@Nullable
	private SceneRecreation sceneRecreation;
	private boolean onlyDrawingTestObjects;

	// The recreated scene's environment, and its area & transition before being made to cover everything instantly
	@Nullable
	private String overrideEnvironmentName;
	@Nullable
	private Environment overriddenEnvironment;
	private Area overriddenEnvironmentArea;
	private boolean overriddenEnvironmentInstant;
	// How far the recreated scene is from where it was captured, which the environment's ground fog moves by, since it's
	// drawn at fixed heights. Its own fog heights are kept to put back.
	private int overrideFogShift;
	private float overriddenFogStart;
	private float overriddenFogEnd;
	// Keeps switching instantly until 117 HD has switched away from it
	@Nullable
	private Environment leavingEnvironment;
	private boolean leavingEnvironmentInstant;

	// Only draw test objects, like the Blindfold plugin, leaving clickboxes & menus alone
	private final RenderCallback onlyDrawTestObjects = new RenderCallback() {
		@Override
		public boolean drawTile(Scene scene, Tile tile) {
			return false;
		}

		@Override
		public boolean drawObject(Scene scene, TileObject object) {
			if (!(object instanceof GameObject))
				return false;
			// Keep the local player
			var renderable = ((GameObject) object).getRenderable();
			if (renderable != null && renderable == client.getLocalPlayer())
				return true;
			// Test objects are drawn in a temporary game object, matched by position
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
			return !floorTileKeys.contains(RecreatedSceneRenderer.packTile(
				tile.getRenderLevel(),
				sceneLocation.getX() + HDUtils.EXTENDED_SCENE_OFFSET,
				sceneLocation.getY() + HDUtils.EXTENDED_SCENE_OFFSET
			));
		}
	};

	// Checked like 117 HD's SceneUploader
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
		public boolean isGapFilled(int sceneX, int sceneY) {
			var ctx = plugin.getSceneContext();
			if (ctx == null || !ctx.fillGaps || ctx.currentArea == null || !ctx.currentArea.fillGaps)
				return false;
			ctx.sceneToWorld(sceneX, sceneY, 0, worldPos);
			return ctx.currentArea.containsPoint(worldPos);
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

	/** Must be called on the client thread */
	public void shutDown() {
		eventBus.unregister(this);
		stopHiding();
		removeRecreatedScene();
		// Nothing will check once stopped
		finishLeavingEnvironment();
	}

	/** Show everything again. Client thread only. */
	void stopHiding() {
		setOnlyDrawingTestObjects(false);
		var panel = spawnerTools.get().getPanel();
		if (panel != null)
			SwingUtilities.invokeLater(() -> panel.showOnlyDrawingTestObjects(false));
	}

	/** The recreated scene's static objects & NPCs. Client thread only. */
	List<StaticObject> getRecreatedStaticObjects() {
		return recreatedStaticObjects;
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

	/** Capture what's drawn around the player to a file. Any thread. */
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

	/** The player's environment, like the tile info overlay shows, or null */
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

	/** Pass the saved captures' file names to the callback on the Swing thread */
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

	/** Delete a saved scene capture's file, then call back on the Swing thread */
	public void deleteSceneCapture(String fileName, Runnable callback) {
		if (!developerMode)
			return;
		executor.execute(() -> {
			try {
				SceneCapture.delete(fileName);
				postMessage("Deleted the scene capture " + fileName);
			} catch (IOException ex) {
				log.error("Failed to delete scene capture {}", fileName, ex);
				postMessage("Failed to delete the scene capture: " + ex.getMessage());
			}
			SwingUtilities.invokeLater(callback);
		});
	}

	/** Recreate a saved capture in the player's region, replacing any earlier one */
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

		// Now, so moving while it spawns doesn't move the rest
		LocalPoint origin = player.getLocalLocation();
		int originPlane = worldView.getPlane();
		int groundHeight = Perspective.getTileHeight(client, origin, originPlane);
		overrideEnvironmentName = capture.environment;
		overrideFogShift = capture.groundHeight == null ? 0 : groundHeight - capture.groundHeight;
		var recreation = new SceneRecreation(capture.name == null ? "the scene capture" : capture.name);
		recreation.terrain = capture.terrain;
		recreation.capturedPlane = capture.capturedAt == null ? 0 : capture.capturedAt.getPlane();
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
				// Static objects can go in 117 HD's extended scene, unlike RuneLiteObjects
				var location = new LocalPoint(x, y, worldView);
				if (getSceneTile(location, originPlane) == null && !location.isInScene()) {
					recreation.skippedOutsideScene++;
					continue;
				}
				int orientation = mod((part.rotation & 3) * 512 + object.modelOrientation, 2048);
				// Everything on the player's plane, which isn't hidden, since heights keep other planes in place
				int height = groundHeight + object.height;
				recreation.pending.add(new PendingScenePart(object, part, location, originPlane, orientation, height));
			}
		}
		recreateFloor(capture, baseX, baseY, originPlane, groundHeight, recreation);
		recreateNpcs(capture, baseX, baseY, originPlane, groundHeight, recreation);

		// Nearest first
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

		// Static objects first, then animated ones once the static scene shows
		boolean placingAnimated = recreation.staticScenePlaced;
		if (placingAnimated && recreatedSceneRenderer.isUploading())
			return;
		var queue = placingAnimated ? recreation.animated : recreation.pending;
		long deadline = System.nanoTime() + SCENE_RECREATION_NANOS_PER_TICK;
		while (recreation.next < queue.size() && System.nanoTime() < deadline) {
			var pending = queue.get(recreation.next++);
			try {
				if (!recreateObject(recreation, pending, placingAnimated))
					recreation.animated.add(pending);
			} catch (RuntimeException ex) {
				log.warn("Unable to recreate object {}", pending.object.id, ex);
				recreation.skippedNoModel++;
			}
		}

		if (recreation.next < queue.size())
			return;
		recreation.next = 0;
		if (!placingAnimated) {
			placeStaticScene(recreation);
			recreation.staticScenePlaced = true;
		} else {
			finishSceneRecreation(recreation);
		}
	}

	/** Recreate part of an object, or return false to leave it for later if it needs a RuneLiteObject */
	private boolean recreateObject(SceneRecreation recreation, PendingScenePart pending, boolean placeAnimated) {
		var spawner = spawnerTools.get();
		var captured = pending.object;

		// The model the client drew, already rotated & bent
		if (captured.model != null) {
			if (getSceneTile(pending.location, pending.plane) == null) {
				recreation.skippedOutsideScene++;
				return true;
			}
			addStaticObject(recreation, pending, captured.model.toModel());
			return true;
		}

		var def = spawner.readTestObjectDefinition(captured.id);
		if (def == null) {
			recreation.skippedUnreadable++;
			return true;
		}
		int[] modelIds = SceneCapture.getModelIds(def, pending.part);
		if (modelIds == null) {
			recreation.skippedNoModel++;
			return true;
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

		// Static scenery where possible, but animations & objects without a tile need RuneLiteObjects
		Tile tile = def.animation == -1 ? getSceneTile(pending.location, pending.plane) : null;
		if (tile != null) {
			Model model = spawner.buildTestObjectModel(
				def,
				modelIds,
				def.mirrored != pending.part.isMirrored(),
				pending.part.isDiagonal(),
				// Static scenery isn't rotated when drawn
				modelData -> SceneCapture.finishModel(modelData, def, quarterTurns, contour, 0, true),
				0,
				false
			);
			if (model == null) {
				recreation.skippedNoModel++;
				return true;
			}
			addStaticObject(recreation, pending, model);
			return true;
		}

		if (!placeAnimated)
			return false;
		if (!pending.location.isInScene()) {
			recreation.skippedOutsideScene++;
			return true;
		}

		// Test objects are recognised by exact position, so move each off the real object's spot and other test objects
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
			// Moved back by the nudge, so pieces still meet without cracks
			modelData -> SceneCapture.finishModel(modelData, def, quarterTurns, contour, nudgeBack, false),
			// Turned by the RuneLiteObject when drawn
			quarterTurns,
			false
		);
		if (model == null) {
			recreation.skippedNoModel++;
			return true;
		}
		var object = spawner.placeTestObject(captured.id, def, model, location, pending.plane, pending.orientation, pending.height);
		if (object != null)
			recreatedSceneObjects.add(object);
		return true;
	}

	private void placeStaticScene(SceneRecreation recreation) {
		log.debug("Built {} recreated static objects in {} ms", recreation.staticObjects.size(), (System.nanoTime() - recreation.startedAt) / 1_000_000);
		recreatedStaticObjects = recreation.staticObjects;
		recreatedFloor = recreation.floor;
		recreatedSceneRenderer.set(recreatedStaticObjects, recreatedFloor, recreation.gapFillers);
		testObjectLights.setStaticObjects(recreatedStaticObjects);
		if (!recreatedFloor.isEmpty() || !recreation.gapFillers.isEmpty()) {
			// 117 HD only asks render callbacks about tiles while uploading, so upload again without them, including
			// where the capture had holes
			Set<Integer> keys = new HashSet<>();
			for (var tile : recreatedFloor)
				keys.add(RecreatedSceneRenderer.packTile(tile.plane, tile.tileExX, tile.tileExY));
			for (var tile : recreation.gapFillers)
				keys.add(RecreatedSceneRenderer.packTile(tile.plane, tile.tileExX, tile.tileExY));
			floorTileKeys = keys;
			renderCallbackManager.register(hideFloorTiles);
			reloadScene();
		}
	}

	private void finishSceneRecreation(SceneRecreation recreation) {
		sceneRecreation = null;
		placeAnimatedNpcs(recreation);
		log.debug("Recreated {} in {} ms", recreation.name, (System.nanoTime() - recreation.startedAt) / 1_000_000);
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

	/** Upload 117 HD's scene again, unless its renderer is gone */
	private void reloadScene() {
		var renderer = plugin.renderer;
		if (renderer != null)
			renderer.reloadScene();
	}

	/** The tile at a local point, including the extended scene, or null */
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

	/** Lay a capture's tiles out on the player's plane, in place of the real tiles */
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

		// Older captures have none
		if (capture.gapFillers == null)
			return;
		for (var captured : capture.gapFillers) {
			int tileExX = (baseX >> Perspective.LOCAL_COORD_BITS) + captured.x + HDUtils.EXTENDED_SCENE_OFFSET;
			int tileExY = (baseY >> Perspective.LOCAL_COORD_BITS) + captured.y + HDUtils.EXTENDED_SCENE_OFFSET;
			if (tileExX < 1 || tileExY < 1 || tileExX >= Constants.EXTENDED_SCENE_SIZE - 1 || tileExY >= Constants.EXTENDED_SCENE_SIZE - 1)
				continue;
			// Taking a real tile's level, if there is one
			Tile tile = getExtendedTile(originPlane, tileExX, tileExY);
			var gapFiller = createFloorTile(captured, tile == null ? originPlane : tile.getRenderLevel(), tileExX, tileExY, groundHeight);
			if (gapFiller != null)
				recreation.gapFillers.add(gapFiller);
		}
	}

	/** Frozen copies of a capture's NPCs, from their definitions, uploaded like static scenery */
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
			recreation.recreatedNpcs++;
			// Animated NPCs need RuneLiteObjects, placed along with animated objects
			if (captured.idleAnimation != null && captured.idleAnimation != -1) {
				recreation.animatedNpcs.add(new PendingNpc(
					captured.id, model, location, originPlane, captured.orientation, groundHeight + captured.height,
					captured.idleAnimation
				));
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
		}
	}

	private void placeAnimatedNpcs(SceneRecreation recreation) {
		var spawner = spawnerTools.get();
		for (var npc : recreation.animatedNpcs) {
			if (!npc.location.isInScene()) {
				recreation.skippedOutsideScene++;
				recreation.recreatedNpcs--;
				continue;
			}
			// Test objects are recognised by exact position, so move each off the NPC's spot and other test objects
			var location = npc.location;
			do {
				location = location.dx(1);
			} while (spawner.isTestObjectAt(location));
			var object = spawner.placeTestNpc(npc.id, npc.model, location, npc.plane, npc.orientation, npc.height, npc.animation);
			if (object != null)
				recreatedSceneObjects.add(object);
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

	/** A captured tile at the player's ground height, or null without a shape */
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

	/** Draw only test objects & the local player. Any thread. */
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
		recreatedSceneRenderer.setHidingRealGapFillers(only);
		if (only) {
			renderCallbackManager.register(onlyDrawTestObjects);
		} else {
			renderCallbackManager.unregister(onlyDrawTestObjects);
			if (plugin.getSceneContext() != null)
				hdInternals.reloadLights();
		}
		// 117 HD only asks render callbacks while uploading, which it can redo without a game reload
		reloadScene();
	}

	/** Fade out undrawn objects' lights, which 117 HD would keep */
	private void hideUndrawnObjectLights() {
		if (plugin.getSceneContext() == null)
			return;
		var scene = client.getTopLevelWorldView().getScene();
		for (var light : hdInternals.getLights())
			if (light.tileObject != null && !light.markedForRemoval && !renderCallbackManager.drawObject(scene, light.tileObject))
				light.markedForRemoval = true;
	}

	/** Light everywhere with the recreated scene's environment, picking up environments.json edits live */
	private void updateEnvironmentOverride() {
		var ctx = plugin.getSceneContext();
		if (ctx == null)
			return;
		if (leavingEnvironment != null && environmentManager.getTargetEnvironment() != leavingEnvironment)
			finishLeavingEnvironment();

		var environment = overrideEnvironmentName == null ? null : hdInternals.findEnvironment(overrideEnvironmentName);
		if (environment != overriddenEnvironment) {
			restoreEnvironment();
			if (environment != null) {
				boolean instant = environment.instantTransition;
				if (environment == leavingEnvironment) {
					instant = leavingEnvironmentInstant;
					leavingEnvironment = null;
				}
				overriddenEnvironment = environment;
				overriddenEnvironmentArea = environment.area;
				overriddenEnvironmentInstant = instant;
				// Keeping the area's name, which can be the environment's
				environment.area = new Area(
					environment.area.name,
					Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE
				);
				environment.instantTransition = true;
				overriddenFogStart = environment.groundFogStart;
				overriddenFogEnd = environment.groundFogEnd;
				environment.groundFogStart += overrideFogShift;
				environment.groundFogEnd += overrideFogShift;
			}
		}
		// 117 HD picks the first containing the camera
		if (environment != null && (ctx.environments.isEmpty() || ctx.environments.get(0) != environment)) {
			ctx.environments.remove(environment);
			ctx.environments.add(0, environment);
		}
	}

	private void restoreEnvironment() {
		if (overriddenEnvironment == null)
			return;
		overriddenEnvironment.area = overriddenEnvironmentArea;
		overriddenEnvironment.groundFogStart = overriddenFogStart;
		overriddenEnvironment.groundFogEnd = overriddenFogEnd;
		// 117 HD only switches away next frame
		finishLeavingEnvironment();
		leavingEnvironment = overriddenEnvironment;
		leavingEnvironmentInstant = overriddenEnvironmentInstant;
		overriddenEnvironment = null;
		var ctx = plugin.getSceneContext();
		if (ctx != null)
			environmentManager.loadSceneEnvironments(ctx);
	}

	private void finishLeavingEnvironment() {
		if (leavingEnvironment == null)
			return;
		leavingEnvironment.instantTransition = leavingEnvironmentInstant;
		leavingEnvironment = null;
	}

	public void removeRecreatedSceneAndNotify() {
		clientThread.invoke(() -> {
			removeRecreatedScene();
			postMessage("Removed the recreated scene");
		});
	}

	/** Must be called on the client thread */
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

	private static void addStaticObject(SceneRecreation recreation, PendingScenePart pending, Model model) {
		var captured = pending.object;
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
	}

	@RequiredArgsConstructor
	private static class SceneRecreation {
		final String name;
		final long startedAt = System.nanoTime();
		final List<PendingScenePart> pending = new ArrayList<>();
		// Placed once the static scene has uploaded
		final List<PendingScenePart> animated = new ArrayList<>();
		boolean staticScenePlaced;
		final List<StaticObject> staticObjects = new ArrayList<>();
		final List<FloorTile> floor = new ArrayList<>();
		final List<FloorTile> gapFillers = new ArrayList<>();
		int next;
		int skippedOutsideScene;
		int skippedUnreadable;
		int skippedNoModel;
		int skippedFloorTiles;
		int recreatedNpcs;
		final List<PendingNpc> animatedNpcs = new ArrayList<>();
		@Nullable
		SceneCapture.Terrain terrain;
		int capturedPlane;
	}

	@RequiredArgsConstructor
	private static class PendingNpc {
		final int id;
		final Model model;
		final LocalPoint location;
		final int plane;
		final int orientation;
		final int height;
		final int animation;
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
