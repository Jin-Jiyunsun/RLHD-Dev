package rs117.hd.dev.spawner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import rs117.hd.HdPlugin;
import rs117.hd.dev.HdInternals;
import rs117.hd.dev.TestObjectLights;
import rs117.hd.dev.TestObjectRenderer;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.LightManager;
import rs117.hd.utils.ModelHash;

import static rs117.hd.utils.MathUtils.*;

/** Spawns objects, plays spotanims & fires projectiles purely visually, for tuning textures & lights */
@Slf4j
@Singleton
public class SpawnerTools {
	// Test projectiles' flight, in client units, with heights above the ground
	// Land on the target tile's ground
	private static final int TEST_PROJECTILE_END_HEIGHT = 0;
	// Lower is flatter
	private static final int TEST_PROJECTILE_SLOPE = 10;
	private static final int TEST_PROJECTILE_START_POS = 64;
	private static final int TEST_PROJECTILE_BASE_CYCLES = 20;
	private static final int TEST_PROJECTILE_CYCLES_PER_TILE = 5;
	private static final int TEST_PROJECTILE_LOOP_DELAY = 25;

	private static final int OBJECT_CONFIG_GROUP = 6;
	// Spread over a few ticks, to avoid a hitch
	private static final long TEST_OBJECT_DEFINITION_NANOS_PER_TICK = 2_000_000;
	// Client ticks between checks for lights.json or model_overrides.json reloads
	private static final int HD_CONFIG_CHECK_TICKS = 50;

	private static final int TEST_SPOTANIM_SLOT = 0;
	// Client cycles, long enough for the previous lights to be marked for removal
	private static final int TEST_SPOTANIM_LOOP_DELAY = 50;

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
	private ClientToolbar clientToolbar;

	@Inject
	private EventBus eventBus;

	@Inject
	private GamevalManager gamevalManager;

	@Inject
	private LightManager lightManager;

	@Inject
	private HdInternals hdInternals;

	@Inject
	private TestObjectRenderer testObjectRenderer;

	@Inject
	private TestObjectLights testObjectLights;

	@Inject
	private SceneTools sceneTools;

	@Inject
	private ObjectSelection objectSelection;

	private boolean started;

	private int testSpotanimId = -1;
	private boolean testSpotanimLoop;
	private int testSpotanimRestartCycle = -1;
	private List<int[]> testSpotanimFrameTimes;
	private int testSpotanimLastFrame;
	private boolean testSpotanimIsPreview;
	private volatile boolean postSpotanimFrameTimings;
	private final AtomicInteger testSpotanimPreviewRequest = new AtomicInteger();

	private NavigationButton objectSpawnerButton;
	@Nullable
	private volatile ObjectSpawnerPanel objectSpawnerPanel;
	private final Map<Integer, SpawnedTestObject> spawnedTestObjects = new LinkedHashMap<>();
	@Nullable
	private HdConfigIds sentHdConfigIds;
	private int nextHdConfigCheckTick;
	private int nextTestObjectHandle;
	private int testProjectileId = -1;
	private boolean testProjectileLoop;
	private boolean testProjectileIsPreview;
	private boolean testProjectileFired;
	private int testProjectileNextCycle;
	@Nullable
	private Projectile testProjectile;
	private volatile int testProjectileDistance = 10;
	private volatile int testProjectileStartHeight = 150;
	private final AtomicInteger testProjectilePreviewRequest = new AtomicInteger();
	private RuneLiteObject testObjectPreview;
	private int testObjectPreviewId = -1;
	private final AtomicInteger testObjectPreviewRequest = new AtomicInteger();
	// Decoded once for the sidebar
	@Nullable
	private Map<Integer, TestObjectDefinition> testObjectDefinitions;
	@Nullable
	private TestObjectDefinitionLoad testObjectDefinitionLoad;
	private boolean loadingTestObjectNames;
	private final List<Consumer<Map<Integer, TestObjectDefinition>>> testObjectDefinitionCallbacks = new ArrayList<>();

	public void startUp() {
		if (started)
			return;
		started = true;
		eventBus.register(this);
		testObjectRenderer.startUp();
		testObjectLights.startUp();
		sceneTools.startUp();

		// Developer mode only, since RuneLite's plugin rules reject spawned content for normal users
		if (developerMode) {
			SwingUtilities.invokeLater(() -> {
				if (objectSpawnerButton != null)
					return;
				objectSpawnerButton = NavigationButton.builder()
					.tooltip("117 HD Spawner")
					.icon(ObjectSpawnerPanel.createIcon())
					// Right below RuneLite's configuration button
					.priority(1)
					.panel(objectSpawnerPanel = new ObjectSpawnerPanel(this, sceneTools, objectSelection))
					.build();
				clientToolbar.addNavigation(objectSpawnerButton);
			});
			objectSelection.startUp();
		}
	}

	public void shutDown() {
		if (!started)
			return;
		started = false;
		eventBus.unregister(this);
		objectSelection.shutDown();
		SwingUtilities.invokeLater(() -> {
			if (objectSpawnerButton != null) {
				clientToolbar.removeNavigation(objectSpawnerButton);
				objectSpawnerButton = null;
				objectSpawnerPanel = null;
				clientThread.invoke(() -> sentHdConfigIds = null);
			}
		});
		clientThread.invoke(() -> {
			stopTestSpotanim();
			stopTestProjectile();
			clearTestObjects();
			sceneTools.shutDown();
			testObjectLights.shutDown();
			testObjectRenderer.shutDown();
		});
	}

	@Subscribe
	public void onPluginChanged(PluginChanged event) {
		// 117 HD's renderer goes with it, so remove everything, including the render callbacks hiding the scene
		if (!(event.getPlugin() instanceof HdPlugin) || event.isLoaded())
			return;
		clientThread.invoke(() -> {
			stopTestSpotanim();
			stopTestProjectile();
			clearTestObjects();
			sceneTools.stopHiding();
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged) {
		// Local coordinates change meaning when a new scene loads
		if (gameStateChanged.getGameState() != GameState.LOADING)
			return;
		stopTestProjectile();
		if (!testObjectRenderer.getTestObjects().isEmpty() || sceneTools.hasRecreatedScene()) {
			clearTestObjects();
			postMessage("Removed test objects due to scene load");
		}
	}

	@Subscribe
	public void onClientTick(ClientTick clientTick) {
		updateTestProjectile();
		updateSpawnerHdConfigIds();
		updateTestObjectDefinitionLoad();

		if (testSpotanimId == -1)
			return;

		Player player = client.getLocalPlayer();
		if (player == null)
			return;

		ActorSpotAnim spotanim = findTestSpotanim(player);
		if (spotanim != null) {
			testSpotanimRestartCycle = -1;
			// Record when each frame starts during the first play
			int elapsedCycles = client.getGameCycle() - spotanim.getStartCycle();
			if (testSpotanimFrameTimes != null && elapsedCycles >= 0 && spotanim.getFrame() != testSpotanimLastFrame) {
				testSpotanimLastFrame = spotanim.getFrame();
				testSpotanimFrameTimes.add(new int[] { testSpotanimLastFrame, elapsedCycles * Constants.CLIENT_TICK_LENGTH });
			}
			return;
		}

		if (testSpotanimFrameTimes != null) {
			if (postSpotanimFrameTimings)
				postSpotanimTimeline(testSpotanimFrameTimes);
			testSpotanimFrameTimes = null;
		}

		if (!testSpotanimLoop) {
			testSpotanimId = -1;
			return;
		}

		int cycle = client.getGameCycle();
		if (testSpotanimRestartCycle == -1) {
			testSpotanimRestartCycle = cycle + TEST_SPOTANIM_LOOP_DELAY;
		} else if (cycle >= testSpotanimRestartCycle) {
			testSpotanimRestartCycle = -1;
			playTestSpotanim();
		}
	}

	@Nullable
	private ActorSpotAnim findTestSpotanim(Player player) {
		for (var s : player.getSpotAnims())
			if (s.getId() == testSpotanimId)
				return s;
		return null;
	}

	private void startTestSpotanim(int id, boolean loop, boolean recordTimeline) {
		if (!developerMode)
			return;
		// From the beginning, lights included, even when replacing the same spotanim
		int previousId = testSpotanimId;
		stopTestSpotanim();
		Player player = client.getLocalPlayer();
		if (previousId != -1 && player != null)
			hdInternals.removeSpotanimLightsImmediately(player, previousId);

		testSpotanimId = id;
		testSpotanimLoop = loop;
		// So hovering doesn't post a timeline
		testSpotanimFrameTimes = recordTimeline ? new ArrayList<>() : null;
		testSpotanimLastFrame = -1;
		playTestSpotanim();
	}

	private void postPlayingSpotanim(int id, boolean loop) {
		String name;
		try (var gamevals = gamevalManager.obtainHandle()) {
			name = gamevals.getSpotanimName(id);
		}
		postMessage("Playing spotanim " + (name == null ? id : name + " (" + id + ")") + (loop ? ", looping" : ""));
	}

	/** Play a spotanim on the local player. Any thread. */
	public void playSpotanim(int id, boolean loop) {
		clientThread.invoke(() -> {
			if (client.getLocalPlayer() == null) {
				postMessage("No local player to play the spotanim on");
				return;
			}
			startTestSpotanim(id, loop, true);
			postPlayingSpotanim(id, loop);
		});
	}

	// Clicked spotanims' frame timings are only posted when turned on
	public void setPostSpotanimFrameTimings(boolean post) {
		postSpotanimFrameTimings = post;
	}

	/** Whether the playing test spotanim loops, unless it's a preview. Any thread. */
	public void setSpotanimLoop(boolean loop) {
		clientThread.invoke(() -> {
			if (testSpotanimId != -1 && !testSpotanimIsPreview)
				testSpotanimLoop = loop;
		});
	}

	/** Preview a spotanim looping on the local player, replacing any test spotanim. Any thread. */
	public void previewSpotanim(int id) {
		int request = testSpotanimPreviewRequest.incrementAndGet();
		clientThread.invoke(() -> {
			if (request != testSpotanimPreviewRequest.get() || client.getLocalPlayer() == null)
				return;
			startTestSpotanim(id, true, false);
			testSpotanimIsPreview = true;
		});
	}

	public void clearSpotanimPreview() {
		testSpotanimPreviewRequest.incrementAndGet();
		clientThread.invoke(() -> {
			if (testSpotanimIsPreview)
				stopTestSpotanim();
		});
	}

	public void stopSpotanimAndNotify() {
		clientThread.invoke(() -> {
			boolean wasPlaying = testSpotanimId != -1;
			stopTestSpotanim();
			postMessage(wasPlaying ? "Stopped test spotanim" : "No test spotanim is playing");
		});
	}

	/** Read the spotanim gamevals off the client thread, for the callback on the Swing thread */
	public void loadSpotanimNames(Consumer<Map<String, Integer>> callback) {
		executor.execute(() -> {
			Map<String, Integer> spotanims;
			try {
				spotanims = hdInternals.readGamevals(HdInternals.GAMEVAL_SPOTANIMS);
			} catch (IOException | RuntimeException ex) {
				log.error("Failed to read spotanim gamevals", ex);
				spotanims = Map.of();
			}
			Map<String, Integer> result = spotanims;
			SwingUtilities.invokeLater(() -> callback.accept(result));
		});
	}

	private void playTestSpotanim() {
		Player player = client.getLocalPlayer();
		if (player == null)
			return;

		// Replays need fresh lights, while the previous play's may still be fading out
		hdInternals.removeSpotanimLightsImmediately(player, testSpotanimId);
		player.createSpotAnim(TEST_SPOTANIM_SLOT, testSpotanimId, 0, 0);

		// Client-side spotanims don't notify plugins, so tell the LightManager directly, unseen by other plugins
		GraphicChanged changed = new GraphicChanged();
		changed.setActor(player);
		lightManager.onGraphicChanged(changed);
	}

	private void stopTestSpotanim() {
		if (testSpotanimId == -1)
			return;

		Player player = client.getLocalPlayer();
		if (player != null && player.hasSpotAnim(testSpotanimId))
			player.removeSpotAnim(TEST_SPOTANIM_SLOT);

		testSpotanimId = -1;
		testSpotanimIsPreview = false;
		testSpotanimLoop = false;
		testSpotanimRestartCycle = -1;
		testSpotanimFrameTimes = null;
	}

	/** Spawn a test object at the local player. Any thread. */
	public void spawnTestObject(int objectId) {
		withTestObjectDefinition(objectId, true, def -> {
			// Keep the same object's preview instead of spawning a duplicate
			RuneLiteObject object;
			if (testObjectPreview != null && testObjectPreviewId == objectId) {
				object = testObjectPreview;
				testObjectPreview = null;
				testObjectPreviewId = -1;
			} else {
				object = createTestObject(objectId, def, true);
				if (object == null)
					return;
			}

			String name;
			try (var gamevals = gamevalManager.obtainHandle()) {
				name = gamevals.getObjectName(objectId);
			}
			Player player = client.getLocalPlayer();
			WorldPoint location = player == null ? null : player.getWorldLocation();
			int handle = nextTestObjectHandle++;
			String label = def.name != null ? def.name : def.displayName != null ? def.displayName : "Object " + objectId;
			spawnedTestObjects.put(handle, new SpawnedTestObject(handle, object, objectId, label));
			notifySpawnedTestObjectsChanged();
			postMessage("Spawned test object " + (name == null ? objectId : name + " (" + objectId + ")") +
						(location == null ? "" : " at " + location));
		});
	}

	public void removeTestObject(int handle) {
		clientThread.invoke(() -> {
			var spawned = spawnedTestObjects.remove(handle);
			if (spawned == null)
				return;
			spawned.object.setActive(false);
			testObjectRenderer.remove(spawned.object);
			notifySpawnedTestObjectsChanged();
		});
	}

	/** Rotate a spawned test object in 90 degree steps, like real objects */
	public void rotateTestObject(int handle, int quarterTurns) {
		clientThread.invoke(() -> {
			var spawned = spawnedTestObjects.get(handle);
			if (spawned == null)
				return;
			spawned.object.setOrientation(mod(spawned.object.getOrientation() + quarterTurns * 512, 2048));
			notifySpawnedTestObjectsChanged();
		});
	}

	private void notifySpawnedTestObjectsChanged() {
		assert client.isClientThread();
		var panel = objectSpawnerPanel;
		if (panel == null)
			return;
		List<SpawnedObjectInfo> infos = new ArrayList<>();
		for (var spawned : spawnedTestObjects.values()) {
			infos.add(new SpawnedObjectInfo(
				spawned.handle,
				spawned.objectId,
				spawned.label,
				spawned.object.getOrientation() / 512 * 90
			));
		}
		SwingUtilities.invokeLater(() -> panel.setSpawnedObjects(infos));
	}

	/** Send the sidebar which IDs have lights or model overrides when they change, which 117 HD doesn't announce */
	private void updateSpawnerHdConfigIds() {
		var panel = objectSpawnerPanel;
		int tick = client.getGameCycle();
		if (panel == null || tick < nextHdConfigCheckTick && sentHdConfigIds != null)
			return;
		nextHdConfigCheckTick = tick + HD_CONFIG_CHECK_TICKS;

		var ids = new HdConfigIds(
			hdInternals.getObjectLightIds(),
			hdInternals.getOverriddenIds(ModelHash.TYPE_OBJECT),
			hdInternals.getGraphicsObjectLightIds(),
			hdInternals.getOverriddenIds(ModelHash.TYPE_GRAPHICS_OBJECT),
			hdInternals.getProjectileLightIds(),
			hdInternals.getOverriddenIds(ModelHash.TYPE_PROJECTILE)
		);
		if (ids.equals(sentHdConfigIds))
			return;
		sentHdConfigIds = ids;
		SwingUtilities.invokeLater(() -> panel.setHdConfigIds(ids));
	}

	/** Fire a projectile from the local player to the tile the set distance ahead. Any thread. */
	public void playProjectile(int id, boolean loop) {
		clientThread.invoke(() -> {
			if (client.getLocalPlayer() == null) {
				postMessage("No local player to fire the projectile from");
				return;
			}
			startTestProjectile(id, loop);
			String name;
			try (var gamevals = gamevalManager.obtainHandle()) {
				name = gamevals.getSpotanimName(id);
			}
			postMessage("Firing projectile " + (name == null ? id : name + " (" + id + ")") + " " +
						testProjectileDistance + " tiles ahead" + (loop ? ", looping" : ""));
		});
	}

	/** Keep firing a projectile as a preview, replacing any test projectile. Any thread. */
	public void previewProjectile(int id) {
		int request = testProjectilePreviewRequest.incrementAndGet();
		clientThread.invoke(() -> {
			if (request != testProjectilePreviewRequest.get() || client.getLocalPlayer() == null)
				return;
			startTestProjectile(id, true);
			testProjectileIsPreview = true;
		});
	}

	public void clearProjectilePreview() {
		testProjectilePreviewRequest.incrementAndGet();
		clientThread.invoke(() -> {
			if (testProjectileIsPreview)
				stopTestProjectile();
		});
	}

	public void stopProjectileAndNotify() {
		clientThread.invoke(() -> {
			boolean wasActive = testProjectileId != -1;
			stopTestProjectile();
			postMessage(wasActive ? "Stopped test projectile" : "No test projectile is active");
		});
	}

	public void setProjectileLoop(boolean loop) {
		clientThread.invoke(() -> {
			if (testProjectileId != -1 && !testProjectileIsPreview)
				testProjectileLoop = loop;
		});
	}

	public void setProjectileStartHeight(int height) {
		testProjectileStartHeight = height;
	}

	public void setProjectileDistance(int tiles) {
		testProjectileDistance = tiles;
	}

	private void startTestProjectile(int id, boolean loop) {
		if (!developerMode)
			return;
		stopTestProjectile();
		testProjectileId = id;
		testProjectileLoop = loop;
		testProjectileFired = false;
		testProjectileNextCycle = client.getGameCycle();
		updateTestProjectile();
	}

	private void stopTestProjectile() {
		// So the client removes it along with its lights
		if (testProjectile != null && testProjectile.getRemainingCycles() > 0)
			testProjectile.setEndCycle(client.getGameCycle());
		testProjectile = null;
		testProjectileId = -1;
		testProjectileIsPreview = false;
		testProjectileLoop = false;
	}

	private void updateTestProjectile() {
		if (testProjectileId == -1)
			return;

		// Wait for the current flight to land
		int cycle = client.getGameCycle();
		if (testProjectile != null) {
			if (testProjectile.getRemainingCycles() > 0)
				return;
			testProjectile = null;
			testProjectileNextCycle = cycle + TEST_PROJECTILE_LOOP_DELAY;
		}

		if (testProjectileFired && !testProjectileLoop) {
			stopTestProjectile();
			return;
		}
		if (cycle >= testProjectileNextCycle)
			fireTestProjectile();
	}

	private void fireTestProjectile() {
		Player player = client.getLocalPlayer();
		if (player == null)
			return;

		// Facing 0 = south, 512 = west
		WorldPoint source = player.getWorldLocation();
		double angle = player.getOrientation() * JAU_TO_RAD;
		int distance = testProjectileDistance;
		WorldPoint target = source
			.dx((int) Math.round(-Math.sin(angle) * distance))
			.dy((int) Math.round(-Math.cos(angle) * distance));

		int cycle = client.getGameCycle();
		Projectile projectile = client.createProjectile(
			testProjectileId,
			source,
			testProjectileStartHeight,
			player,
			target,
			TEST_PROJECTILE_END_HEIGHT,
			null,
			cycle,
			cycle + TEST_PROJECTILE_BASE_CYCLES + distance * TEST_PROJECTILE_CYCLES_PER_TILE,
			TEST_PROJECTILE_SLOPE,
			TEST_PROJECTILE_START_POS
		);
		client.getProjectiles().addLast(projectile);
		testProjectile = projectile;
		testProjectileFired = true;

		// Client-side projectiles may not notify plugins, so tell the LightManager directly, unseen by other plugins
		ProjectileMoved moved = new ProjectileMoved();
		moved.setProjectile(projectile);
		moved.setPosition(LocalPoint.fromWorld(client.getTopLevelWorldView(), target));
		moved.setZ(0);
		lightManager.onProjectileMoved(moved);
	}

	/** Preview an object at the local player, replacing any previous preview. Any thread. */
	public void previewTestObject(int objectId) {
		int request = testObjectPreviewRequest.incrementAndGet();
		withTestObjectDefinition(objectId, false, def -> {
			// Replaced or cleared while loading
			if (request != testObjectPreviewRequest.get())
				return;
			removeTestObjectPreview();
			testObjectPreview = createTestObject(objectId, def, false);
			testObjectPreviewId = testObjectPreview == null ? -1 : objectId;
		});
	}

	public void clearTestObjectPreview() {
		testObjectPreviewRequest.incrementAndGet();
		clientThread.invoke(this::removeTestObjectPreview);
	}

	private void removeTestObjectPreview() {
		if (testObjectPreview == null)
			return;
		testObjectPreview.setActive(false);
		testObjectRenderer.remove(testObjectPreview);
		testObjectPreview = null;
		testObjectPreviewId = -1;
	}

	private void withTestObjectDefinition(int objectId, boolean reportErrors, Consumer<TestObjectDefinition> callback) {
		clientThread.invoke(() -> {
			var def = readTestObjectDefinition(objectId);
			if (def != null) {
				callback.accept(def);
			} else if (reportErrors) {
				postMessage("Object " + objectId + " has no models, or couldn't be read from the client");
			}
		});
	}

	/** Decode an object's definition from the client's cache data. Client thread only. */
	@Nullable
	TestObjectDefinition readTestObjectDefinition(int objectId) {
		assert client.isClientThread();
		var cached = testObjectDefinitions == null ? null : testObjectDefinitions.get(objectId);
		if (cached != null)
			return cached;
		byte[] data = client.getIndexConfig().loadData(OBJECT_CONFIG_GROUP, objectId);
		return data == null ? null : ObjectDefinitionParser.parse(objectId, data);
	}

	/** Decode every object with models for the callback on the Swing thread, or null if unreadable. Any thread. */
	public void loadTestObjectDefinitions(Consumer<Map<Integer, TestObjectDefinition>> callback) {
		clientThread.invoke(() -> {
			var definitions = testObjectDefinitions;
			if (definitions != null) {
				SwingUtilities.invokeLater(() -> callback.accept(definitions));
				return;
			}
			testObjectDefinitionCallbacks.add(callback);
			if (testObjectDefinitionLoad != null || loadingTestObjectNames)
				return;

			// Gamevals off the client thread, then a batch of definitions per tick
			loadingTestObjectNames = true;
			executor.execute(() -> {
				Map<Integer, String> names = new HashMap<>();
				try {
					hdInternals.readGamevals(HdInternals.GAMEVAL_OBJECTS).forEach((name, id) -> names.putIfAbsent(id, name));
				} catch (IOException | RuntimeException ex) {
					log.error("Failed to read object gamevals", ex);
				}
				clientThread.invoke(() -> {
					loadingTestObjectNames = false;
					int[] ids = client.getIndexConfig().getFileIds(OBJECT_CONFIG_GROUP);
					if (ids == null) {
						finishTestObjectDefinitionLoad(null);
						return;
					}
					testObjectDefinitionLoad = new TestObjectDefinitionLoad(ids, names);
				});
			});
		});
	}

	private void updateTestObjectDefinitionLoad() {
		var load = testObjectDefinitionLoad;
		if (load == null)
			return;

		long deadline = System.nanoTime() + TEST_OBJECT_DEFINITION_NANOS_PER_TICK;
		for (int decoded = 0; load.next < load.ids.length; load.next++, decoded++) {
			if (decoded % 100 == 0 && decoded > 0 && System.nanoTime() > deadline)
				break;
			int id = load.ids[load.next];
			byte[] data = client.getIndexConfig().loadData(OBJECT_CONFIG_GROUP, id);
			var def = data == null ? null : ObjectDefinitionParser.parse(id, data);
			if (def != null) {
				def.name = load.names.get(id);
				load.definitions.put(id, def);
			}
		}
		if (load.next == load.ids.length) {
			testObjectDefinitionLoad = null;
			finishTestObjectDefinitionLoad(Collections.unmodifiableMap(load.definitions));
		}
	}

	private void finishTestObjectDefinitionLoad(@Nullable Map<Integer, TestObjectDefinition> definitions) {
		testObjectDefinitions = definitions;
		var callbacks = new ArrayList<>(testObjectDefinitionCallbacks);
		testObjectDefinitionCallbacks.clear();
		SwingUtilities.invokeLater(() -> callbacks.forEach(callback -> callback.accept(definitions)));
	}

	@RequiredArgsConstructor
	private static class TestObjectDefinitionLoad {
		final int[] ids;
		final Map<Integer, String> names;
		final Map<Integer, TestObjectDefinition> definitions = new HashMap<>();
		int next;
	}

	public void clearTestObjectsAndNotify() {
		clientThread.invoke(() -> {
			int count = testObjectRenderer.getTestObjects().size();
			clearTestObjects();
			postMessage("Removed " + count + " test object" + (count == 1 ? "" : "s"));
		});
	}

	@Nullable
	private RuneLiteObject createTestObject(int objectId, TestObjectDefinition def, boolean reportErrors) {
		assert client.isClientThread();
		if (!developerMode)
			return null;
		Player player = client.getLocalPlayer();
		if (player == null) {
			if (reportErrors)
				postMessage("No local player to spawn the object at");
			return null;
		}

		Model model = buildTestObjectModel(def, def.models, false, false, null, 0, reportErrors);
		if (model == null)
			return null;

		var worldView = client.getTopLevelWorldView();
		LocalPoint location = LocalPoint.fromWorld(worldView, player.getWorldLocation());
		if (location == null) {
			if (reportErrors)
				postMessage("The player isn't in the loaded scene");
			return null;
		}
		// Test objects are recognised by exact position, so give each its own, off the tile centre, in invisible steps
		do {
			location = location.dx(1);
		} while (testObjectRenderer.isTestObjectAt(location.getX(), location.getY()));

		return placeTestObject(objectId, def, model, location, worldView.getPlane(), 0, null);
	}

	/**
	 * Build a model with the definition's model changes like the client. Mirroring, diagonal turns & finishing are for
	 * recreated objects. Models turned when drawn are lit turned the other way, to end up lit like the client's.
	 */
	@Nullable
	Model buildTestObjectModel(
		TestObjectDefinition def,
		int[] modelIds,
		boolean mirror,
		boolean diagonal,
		@Nullable UnaryOperator<ModelData> finish,
		int drawnQuarterTurns,
		boolean reportErrors
	) {
		ModelData[] models = new ModelData[modelIds.length];
		for (int i = 0; i < models.length; i++) {
			models[i] = client.loadModelData(modelIds[i]);
			if (models[i] == null) {
				if (reportErrors)
					postMessage("Unable to load model " + modelIds[i]);
				return null;
			}
		}

		ModelData modelData = models.length == 1 && !mirror && !diagonal ? models[0] : client.mergeModels(models);
		if (mirror)
			SceneCapture.mirror(modelData);
		if (diagonal)
			SceneCapture.rotateDiagonal(modelData);
		if (def.recolorFrom != null && def.recolorTo != null) {
			modelData = modelData.cloneColors();
			for (int i = 0; i < min(def.recolorFrom.length, def.recolorTo.length); i++)
				modelData.recolor(def.recolorFrom[i], def.recolorTo[i]);
		}
		// Untextured models can't have their textures cloned
		if (def.retextureFrom != null && def.retextureTo != null && modelData.getFaceTextures() != null) {
			modelData = modelData.cloneTextures();
			for (int i = 0; i < min(def.retextureFrom.length, def.retextureTo.length); i++)
				modelData.retexture(def.retextureFrom[i], def.retextureTo[i]);
		}
		if (finish != null) {
			// Along the world's axes after rotating, like the client
			modelData = finish.apply(modelData.cloneVertices());
		} else if (def.scale != null || def.offset != null) {
			modelData = modelData.cloneVertices();
			if (def.scale != null)
				modelData.scale(def.scale[0], def.scale[1], def.scale[2]);
			if (def.offset != null)
				modelData.translate(def.offset[0], def.offset[1], def.offset[2]);
		}

		// Like ModelData.rotateY90Ccw and the others turn vertices
		int lightX = ModelData.DEFAULT_X, lightZ = ModelData.DEFAULT_Z;
		switch (-drawnQuarterTurns & 3) {
			case 1:
				lightX = ModelData.DEFAULT_Z;
				lightZ = -ModelData.DEFAULT_X;
				break;
			case 2:
				lightX = -ModelData.DEFAULT_X;
				lightZ = -ModelData.DEFAULT_Z;
				break;
			case 3:
				lightX = -ModelData.DEFAULT_Z;
				lightZ = ModelData.DEFAULT_X;
				break;
		}
		return modelData.light(
			ModelData.DEFAULT_AMBIENT + def.ambient,
			ModelData.DEFAULT_CONTRAST + def.contrast,
			lightX,
			ModelData.DEFAULT_Y,
			lightZ
		);
	}

	/** Spawn a test object with a built model, where there's no other test object. Null height follows the ground. */
	@Nullable
	RuneLiteObject placeTestObject(
		int objectId,
		TestObjectDefinition def,
		Model model,
		LocalPoint location,
		int plane,
		int orientation,
		@Nullable Integer height
	) {
		if (!developerMode)
			return null;
		// Purely visual, with no clickbox or menu entries
		RuneLiteObject object = client.createRuneLiteObject();
		object.setModel(model);
		if (def.animation != -1)
			object.setAnimationController(new AnimationController(client, def.animation));
		object.setShouldLoop(true);
		object.setDrawFrontTilesFirst(false);
		object.setLocation(location, plane);
		object.setOrientation(orientation);
		if (height != null)
			object.setZ(height);
		object.setActive(true);
		testObjectRenderer.add(object, objectId);
		return object;
	}

	boolean isTestObjectAt(LocalPoint location) {
		return testObjectRenderer.isTestObjectAt(location.getX(), location.getY());
	}

	private void clearTestObjects() {
		for (var object : testObjectRenderer.getTestObjects().keySet())
			object.setActive(false);
		testObjectRenderer.clear();
		testObjectPreview = null;
		testObjectPreviewId = -1;
		spawnedTestObjects.clear();
		sceneTools.removeRecreatedScene();
		notifySpawnedTestObjectsChanged();
	}

	@RequiredArgsConstructor
	private static class SpawnedTestObject {
		final int handle;
		final RuneLiteObject object;
		final int objectId;
		final String label;
	}

	/** A spawned test object for the sidebar, safe on the Swing thread */
	@RequiredArgsConstructor
	public static class SpawnedObjectInfo {
		public final int handle;
		public final int objectId;
		public final String label;
		public final int rotationDegrees;
	}

	/** IDs with lights or model overrides, per kind of sidebar entry */
	@lombok.Value
	public static class HdConfigIds {
		public Set<Integer> objectLights;
		public Set<Integer> objectOverrides;
		public Set<Integer> spotanimLights;
		public Set<Integer> spotanimOverrides;
		public Set<Integer> projectileLights;
		public Set<Integer> projectileOverrides;
	}

	private void postSpotanimTimeline(List<int[]> frameTimes) {
		if (frameTimes.isEmpty())
			return;

		int[] last = frameTimes.get(frameTimes.size() - 1);

		// Maya animations advance one frame per client tick
		boolean onePerTick = frameTimes.stream().allMatch(e -> e[1] == e[0] * Constants.CLIENT_TICK_LENGTH);
		if (onePerTick) {
			postMessage(String.format(
				"Spotanim runs one frame per client tick: frame N starts at N * %d ms. Frames 0-%d, ending around %d ms",
				Constants.CLIENT_TICK_LENGTH, last[0], last[1] + Constants.CLIENT_TICK_LENGTH
			));
			return;
		}

		postMessage("Spotanim frame timeline (frame @ ms since start), " + frameTimes.size() + " frames over " + last[1] + "+ ms:");
		var sb = new StringBuilder();
		for (int i = 0; i < frameTimes.size(); i++) {
			int[] entry = frameTimes.get(i);
			if (sb.length() > 0)
				sb.append(", ");
			sb.append(entry[0]).append(" @ ").append(entry[1]);
			if (i % 10 == 9 || i == frameTimes.size() - 1) {
				postMessage(sb.toString());
				log.debug("Spotanim timeline: {}", sb);
				sb.setLength(0);
			}
		}
	}

	@Nullable
	ObjectSpawnerPanel getPanel() {
		return objectSpawnerPanel;
	}

	void postMessage(String message) {
		clientThread.invoke(() -> client.addChatMessage(
			ChatMessageType.GAMEMESSAGE,
			"117 HD",
			"<col=006600>[117 HD] " + message + "</col>",
			"117 HD"
		));
	}
}
