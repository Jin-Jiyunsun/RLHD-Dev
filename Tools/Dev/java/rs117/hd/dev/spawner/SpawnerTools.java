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
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import rs117.hd.dev.HdInternals;
import rs117.hd.dev.TestObjectLights;
import rs117.hd.dev.TestObjectRenderer;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.LightManager;
import rs117.hd.utils.ModelHash;

import static rs117.hd.utils.MathUtils.*;

/**
 * Spawns objects, plays spotanims and fires projectiles purely visually on the client, for tuning their textures & lights.
 * Driven by the {@link ObjectSpawnerPanel} sidebar.
 */
@Slf4j
@Singleton
public class SpawnerTools {
	// Default flight settings for test projectiles, in the units used by the client. Heights are above the ground.
	// Land on the target tile's ground
	private static final int TEST_PROJECTILE_END_HEIGHT = 0;
	// How much projectiles arc on the way, lower is flatter
	private static final int TEST_PROJECTILE_SLOPE = 10;
	private static final int TEST_PROJECTILE_START_POS = 64;
	private static final int TEST_PROJECTILE_BASE_CYCLES = 20;
	private static final int TEST_PROJECTILE_CYCLES_PER_TILE = 5;
	private static final int TEST_PROJECTILE_LOOP_DELAY = 25;

	// Objects' definitions are in this group of the client's config cache index
	private static final int OBJECT_CONFIG_GROUP = 6;
	// Spread decoding every object's definition over a few ticks, to avoid a hitch
	private static final long TEST_OBJECT_DEFINITION_NANOS_PER_TICK = 2_000_000;
	// How often to check whether lights.json or model_overrides.json have been reloaded, in client ticks
	private static final int HD_CONFIG_CHECK_TICKS = 50;

	private static final int TEST_SPOTANIM_SLOT = 0;
	// Pause between loops in client cycles (20 ms each), long enough for the previous lights to be marked for removal
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
	// Height above the ground which test projectiles start from, set from the sidebar
	private volatile int testProjectileStartHeight = 150;
	private final AtomicInteger testProjectilePreviewRequest = new AtomicInteger();
	private RuneLiteObject testObjectPreview;
	private int testObjectPreviewId = -1;
	private final AtomicInteger testObjectPreviewRequest = new AtomicInteger();
	// Every object definition with models, decoded from the client's cache for the sidebar once, then kept
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

		// Swing components must only be created and modified on the Swing thread. The spawner must stay developer-mode only,
		// since RuneLite's plugin rules reject user-entered IDs and spawned content for normal users.
		if (developerMode) {
			SwingUtilities.invokeLater(() -> {
				if (objectSpawnerButton != null)
					return;
				objectSpawnerButton = NavigationButton.builder()
					.tooltip("117 HD Spawner")
					.icon(ObjectSpawnerPanel.createIcon())
					// Sorted by ascending priority, so this goes right below RuneLite's configuration button at priority 0
					.priority(1)
					.panel(objectSpawnerPanel = new ObjectSpawnerPanel(this))
					.build();
				clientToolbar.addNavigation(objectSpawnerButton);
			});
		}
	}

	public void shutDown() {
		if (!started)
			return;
		started = false;
		eventBus.unregister(this);
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
			testObjectLights.shutDown();
			testObjectRenderer.shutDown();
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged) {
		// Test objects are positioned in local coordinates, which change meaning when a new scene loads
		if (gameStateChanged.getGameState() != GameState.LOADING)
			return;
		stopTestProjectile();
		if (!testObjectRenderer.getTestObjects().isEmpty()) {
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
			// Record when each frame starts during the first play, relative to the spotanim's start cycle
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
		// Start from the beginning, including the lights, even when replacing the same spotanim or its preview
		int previousId = testSpotanimId;
		stopTestSpotanim();
		Player player = client.getLocalPlayer();
		if (previousId != -1 && player != null)
			hdInternals.removeSpotanimLightsImmediately(player, previousId);

		testSpotanimId = id;
		testSpotanimLoop = loop;
		// Previews skip recording, so hovering doesn't post a timeline to the chat
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

	/**
	 * Play a spotanim on the local player. Can be called from any thread.
	 */
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

	/**
	 * Change whether the playing test spotanim loops, unless it's a hover preview. Can be called from any thread.
	 */
	public void setSpotanimLoop(boolean loop) {
		clientThread.invoke(() -> {
			if (testSpotanimId != -1 && !testSpotanimIsPreview)
				testSpotanimLoop = loop;
		});
	}

	/**
	 * Show a looping preview of the spotanim on the local player, replacing whichever test spotanim is playing.
	 * Can be called from any thread.
	 */
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

	/**
	 * Read the spotanim gamevals off the client thread, then pass them to the callback on the Swing thread.
	 */
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

		// Replays, such as loops, need fresh lights rather than the previous play's, which may still be fading out
		hdInternals.removeSpotanimLightsImmediately(player, testSpotanimId);
		player.createSpotAnim(TEST_SPOTANIM_SLOT, testSpotanimId, 0, 0);

		// Client-side spotanims don't notify plugins, so let the LightManager know to attach lights. This goes to it
		// directly rather than through the event bus, so other plugins never mistake the test spotanim for a real one.
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

	/**
	 * Spawn a test object with the given object ID at the local player's location. Can be called from any thread.
	 */
	public void spawnTestObject(int objectId) {
		withTestObjectDefinition(objectId, true, def -> {
			// Keep the preview of the same object instead of spawning a duplicate on top of it
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

	/**
	 * Rotate a spawned test object in 90 degree steps, like real objects
	 */
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

	/**
	 * Send the sidebar which IDs have lights or model overrides whenever they change, since they load in the background
	 * at startup and again whenever their JSON files are saved. 117 HD doesn't say when that happens, so check every so often.
	 */
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

	/**
	 * Fire a projectile from the local player to the tile the configured distance ahead. Can be called from any thread.
	 */
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

	/**
	 * Keep firing the projectile as a preview, replacing whichever test projectile is active. Can be called from any thread.
	 */
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
		// End the flight early, so the client removes it along with its lights
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

		// Aim at the tile the set distance ahead, in the direction the player is facing (0 = south, 512 = west)
		WorldPoint source = player.getWorldLocation();
		double angle = player.getOrientation() * JAU_TO_RAD;
		int distance = testProjectileDistance;
		WorldPoint target = source
			.dx((int) Math.round(-Math.sin(angle) * distance))
			.dy((int) Math.round(-Math.cos(angle) * distance));

		// Purely visual: a client-side projectile isn't sent to the server
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

		// Client-side projectiles may not notify plugins, so let the LightManager know to attach lights. This goes to it
		// directly rather than through the event bus, so other plugins never mistake the test projectile for a real one.
		ProjectileMoved moved = new ProjectileMoved();
		moved.setProjectile(projectile);
		moved.setPosition(LocalPoint.fromWorld(client.getTopLevelWorldView(), target));
		moved.setZ(0);
		lightManager.onProjectileMoved(moved);
	}

	/**
	 * Show a temporary preview of the object at the local player's location, replacing any previous preview.
	 * Can be called from any thread.
	 */
	public void previewTestObject(int objectId) {
		int request = testObjectPreviewRequest.incrementAndGet();
		withTestObjectDefinition(objectId, false, def -> {
			// Skip previews which have already been replaced or cleared while loading
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

	/**
	 * Decode one object's definition from the cache data the client has loaded. Must be called on the client thread.
	 */
	@Nullable
	private TestObjectDefinition readTestObjectDefinition(int objectId) {
		assert client.isClientThread();
		var cached = testObjectDefinitions == null ? null : testObjectDefinitions.get(objectId);
		if (cached != null)
			return cached;
		byte[] data = client.getIndexConfig().loadData(OBJECT_CONFIG_GROUP, objectId);
		return data == null ? null : ObjectDefinitionParser.parse(objectId, data);
	}

	/**
	 * Decode every object with models for the sidebar, then pass them to the callback on the Swing thread.
	 * The callback receives null if the client's cache data couldn't be read. Can be called from any thread.
	 */
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

			// Read the gameval names off the client thread, then decode the definitions a batch per tick
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
			// Checking the time every so often is enough, and cheaper than every definition
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

		Model model = buildTestObjectModel(def, def.models, reportErrors);
		if (model == null)
			return null;

		var worldView = client.getTopLevelWorldView();
		LocalPoint location = LocalPoint.fromWorld(worldView, player.getWorldLocation());
		if (location == null) {
			if (reportErrors)
				postMessage("The player isn't in the loaded scene");
			return null;
		}
		// The renderer recognises test objects by their exact position, so give each its own, off the tile centre where
		// game graphics usually sit. Each step is 1/128 of a tile, far too small to see.
		do {
			location = location.dx(1);
		} while (testObjectRenderer.isTestObjectAt(location.getX(), location.getY()));

		return placeTestObject(objectId, def, model, location, worldView.getPlane());
	}

	/**
	 * Build an object's model from some of its model IDs, applying the definition's model changes like the client does
	 */
	@Nullable
	private Model buildTestObjectModel(TestObjectDefinition def, int[] modelIds, boolean reportErrors) {
		ModelData[] models = new ModelData[modelIds.length];
		for (int i = 0; i < models.length; i++) {
			models[i] = client.loadModelData(modelIds[i]);
			if (models[i] == null) {
				if (reportErrors)
					postMessage("Unable to load model " + modelIds[i]);
				return null;
			}
		}

		// Apply the object definition's model changes the same way the client does
		ModelData modelData = models.length == 1 ? models[0] : client.mergeModels(models);
		if (def.recolorFrom != null && def.recolorTo != null) {
			modelData = modelData.cloneColors();
			for (int i = 0; i < min(def.recolorFrom.length, def.recolorTo.length); i++)
				modelData.recolor(def.recolorFrom[i], def.recolorTo[i]);
		}
		if (def.retextureFrom != null && def.retextureTo != null) {
			modelData = modelData.cloneTextures();
			for (int i = 0; i < min(def.retextureFrom.length, def.retextureTo.length); i++)
				modelData.retexture(def.retextureFrom[i], def.retextureTo[i]);
		}
		if (def.scale != null || def.offset != null) {
			modelData = modelData.cloneVertices();
			if (def.scale != null)
				modelData.scale(def.scale[0], def.scale[1], def.scale[2]);
			if (def.offset != null)
				modelData.translate(def.offset[0], def.offset[1], def.offset[2]);
		}

		return modelData.light(
			ModelData.DEFAULT_AMBIENT + def.ambient,
			ModelData.DEFAULT_CONTRAST + def.contrast,
			ModelData.DEFAULT_X,
			ModelData.DEFAULT_Y,
			ModelData.DEFAULT_Z
		);
	}

	/**
	 * Spawn a test object with a built model. Its location must be free of other test objects.
	 */
	@Nullable
	private RuneLiteObject placeTestObject(int objectId, TestObjectDefinition def, Model model, LocalPoint location, int plane) {
		if (!developerMode)
			return null;
		// Purely visual: a RuneLiteObject has no clickbox or menu entries, and nothing is sent to the server
		RuneLiteObject object = client.createRuneLiteObject();
		object.setModel(model);
		if (def.animation != -1)
			object.setAnimationController(new AnimationController(client, def.animation));
		object.setShouldLoop(true);
		object.setDrawFrontTilesFirst(false);
		object.setLocation(location, plane);
		object.setActive(true);
		testObjectRenderer.add(object, objectId);
		return object;
	}

	private void clearTestObjects() {
		for (var object : testObjectRenderer.getTestObjects().keySet())
			object.setActive(false);
		testObjectRenderer.clear();
		testObjectPreview = null;
		testObjectPreviewId = -1;
		spawnedTestObjects.clear();
		notifySpawnedTestObjectsChanged();
	}

	@RequiredArgsConstructor
	private static class SpawnedTestObject {
		final int handle;
		final RuneLiteObject object;
		final int objectId;
		final String label;
	}

	/**
	 * A snapshot of a spawned test object for the sidebar, safe to use on the Swing thread
	 */
	@RequiredArgsConstructor
	public static class SpawnedObjectInfo {
		public final int handle;
		public final int objectId;
		public final String label;
		public final int rotationDegrees;
	}

	/**
	 * IDs with lights.json lights or model_overrides.json overrides, per kind of entry in the sidebar
	 */
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

		// Maya animations advance one frame per client tick, so a full timeline would just be frame * tick length
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

	void postMessage(String message) {
		clientThread.invoke(() -> client.addChatMessage(
			ChatMessageType.GAMEMESSAGE,
			"117 HD",
			"<col=006600>[117 HD] " + message + "</col>",
			"117 HD"
		));
	}
}
