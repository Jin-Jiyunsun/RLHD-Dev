package rs117.hd.dev;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.hooks.*;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import rs117.hd.renderer.zone.RecreatedSceneRenderer;
import rs117.hd.utils.ModelHash;

/**
 * Wraps the client's draw callbacks so test objects reach 117 HD in stand-in tile objects whose hashes carry the IDs
 * they stand in for, picking those objects' model overrides. Also draws recreated scenes' zones.
 */
@Slf4j
@Singleton
public class TestObjectRenderer {
	@Inject
	private Client client;

	@Inject
	private EventBus eventBus;

	@Inject
	private RecreatedSceneRenderer recreatedSceneRenderer;

	// Read while drawing on other threads
	private final Map<RuneLiteObject, Integer> testObjects = new ConcurrentHashMap<>();
	// By exact position too, since what's drawn isn't always the RuneLiteObject itself
	private final Map<Long, Integer> testObjectPositions = new ConcurrentHashMap<>();

	@Nullable
	private DrawCallbacks wrapped;
	@Nullable
	private DrawCallbacks wrapper;

	public void startUp() {
		eventBus.register(this);
	}

	public void shutDown() {
		eventBus.unregister(this);
		clear();
		unwrap();
	}

	/** Draw the object as the given object ID. It must be in place before it's added, and never move afterwards. */
	public void add(RuneLiteObject object, int objectId) {
		testObjects.put(object, objectId);
		testObjectPositions.put(packPosition(object.getX(), object.getY()), objectId);
	}

	public void remove(RuneLiteObject object) {
		if (testObjects.remove(object) != null)
			testObjectPositions.remove(packPosition(object.getX(), object.getY()));
	}

	public void clear() {
		testObjects.clear();
		testObjectPositions.clear();
	}

	public Map<RuneLiteObject, Integer> getTestObjects() {
		return testObjects;
	}

	public boolean isTestObjectAt(int localX, int localZ) {
		return testObjectPositions.containsKey(packPosition(localX, localZ));
	}

	/** The object ID a test object stands in for, by identity or exact position, or -1 */
	public int getTestObjectId(@Nullable Renderable renderable, int localX, int localZ) {
		if (testObjects.isEmpty() || renderable instanceof Actor || renderable instanceof Projectile || renderable instanceof DynamicObject)
			return -1;
		Integer id = renderable instanceof RuneLiteObject ? testObjects.get(renderable) : null;
		if (id == null)
			id = testObjectPositions.get(packPosition(localX, localZ));
		return id == null ? -1 : id;
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event) {
		// Only while there's something to draw or hide, again whenever 117 HD replaces its renderer
		if (testObjects.isEmpty() && !recreatedSceneRenderer.hasContent() && !recreatedSceneRenderer.isHidingRealGapFillers()) {
			unwrap();
		} else if (client.getDrawCallbacks() != wrapper) {
			wrap();
		}
	}

	private void wrap() {
		DrawCallbacks current = client.getDrawCallbacks();
		if (current == null)
			return;
		wrapped = current;
		wrapper = (DrawCallbacks) Proxy.newProxyInstance(
			DrawCallbacks.class.getClassLoader(),
			new Class<?>[] { DrawCallbacks.class },
			new DrawCallbacksHandler(current)
		);
		client.setDrawCallbacks(wrapper);
	}

	private void unwrap() {
		// Leave whatever 117 HD has put in its place alone
		if (wrapper != null && client.getDrawCallbacks() == wrapper)
			client.setDrawCallbacks(wrapped);
		wrapper = null;
		wrapped = null;
	}

	private class DrawCallbacksHandler implements InvocationHandler {
		private final DrawCallbacks target;

		DrawCallbacksHandler(DrawCallbacks target) {
			this.target = target;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			switch (method.getName()) {
				case "drawDynamic":
					// ([renderThreadId,] projection, scene, tileObject, renderable, model, orientation, x, y, z)
					int i = args.length - 9;
					if (i == 0 || i == 1)
						args[i + 2] = standIn((TileObject) args[i + 2], (Renderable) args[i + 3], (int) args[i + 6], (int) args[i + 8]);
					break;
				case "drawTemp":
					// (projection, scene, gameObject, model, orientation, x, y, z)
					if (args.length == 8) {
						var gameObject = (GameObject) args[2];
						args[2] = standIn(gameObject, gameObject.getRenderable(), (int) args[5], (int) args[7]);
					}
					break;
				case "equals":
					return proxy == args[0];
				case "hashCode":
					return System.identityHashCode(proxy);
			}
			boolean drawingZone = method.getName().equals("drawZoneOpaque");
			if (drawingZone) {
				// (projection, scene, zx, zz)
				recreatedSceneRenderer.beforeDrawZoneOpaque((Scene) args[1], (int) args[2], (int) args[3]);
			}
			Object result;
			try {
				result = method.invoke(target, args);
			} catch (InvocationTargetException ex) {
				throw ex.getCause();
			} finally {
				if (drawingZone)
					recreatedSceneRenderer.restoreRealGapFillers();
			}
			// Without letting a problem with the recreated scene stop 117 HD
			try {
				switch (method.getName()) {
					case "preSceneDraw":
						// (scene, [projection,] camera..., hideRoofIds)
						recreatedSceneRenderer.afterPreSceneDraw((Scene) args[0]);
						break;
					case "drawZoneOpaque":
						// (projection, scene, zx, zz)
						recreatedSceneRenderer.afterDrawZoneOpaque((Scene) args[1], (int) args[2], (int) args[3]);
						break;
					case "drawZoneAlpha":
						// (projection, scene, level, zx, zz)
						recreatedSceneRenderer.afterDrawZoneAlpha((Scene) args[1], (int) args[2], (int) args[3], (int) args[4]);
						break;
					case "drawPass":
						// (projection, scene, pass)
						recreatedSceneRenderer.afterDrawPass((Scene) args[1], (int) args[2]);
						break;
				}
			} catch (Exception ex) {
				log.error("Error drawing the recreated scene in {}", method.getName(), ex);
			}
			return result;
		}
	}

	private TileObject standIn(TileObject tileObject, Renderable renderable, int localX, int localZ) {
		int objectId = getTestObjectId(renderable, localX, localZ);
		if (objectId == -1 || tileObject == null)
			return tileObject;
		// Keep the position & plane bits, replacing the type & ID
		long hash = tileObject.getHash() & 0xFFFF | (long) ModelHash.TYPE_OBJECT << 16 | (long) objectId << 20;
		Function<String, Object> answers = name -> name.equals("getHash") ? hash : null;
		if (tileObject instanceof GameObject)
			return Reflection.wrap(GameObject.class, (GameObject) tileObject, answers);
		return Reflection.wrap(TileObject.class, tileObject, answers);
	}

	private static long packPosition(int localX, int localZ) {
		return (long) localX << 32 | localZ & 0xFFFFFFFFL;
	}
}
