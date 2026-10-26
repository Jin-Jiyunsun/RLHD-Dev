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
 * Draws client-side test objects, such as spawned objects, as the object IDs they stand in for, so 117 HD applies those
 * objects' model overrides to them.
 * <p>
 * 117 HD picks a model override by the object ID in the hash of the tile object it's asked to draw, which for a
 * RuneLiteObject says nothing about which object it shows. So while there are test objects, the client's draw callbacks
 * are wrapped, and test objects are passed on to 117 HD's renderer in a stand-in tile object whose hash has their ID.
 * The wrapper also draws a recreated scene capture's zones, through {@link RecreatedSceneRenderer}.
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

	// Test objects and the object IDs they stand in for. Read while drawing on other threads.
	private final Map<RuneLiteObject, Integer> testObjects = new ConcurrentHashMap<>();
	// The same objects by their exact local position, since what's drawn isn't always the RuneLiteObject itself
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

	/**
	 * Draw the object as the given object ID. It must be in place before it's added, and never move afterwards.
	 */
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

	/**
	 * The object ID a test object stands in for, matched by identity or else by its exact local position,
	 * or -1 if the renderable isn't a test object
	 */
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
		// Only wrap while there's something to draw, and wrap again whenever 117 HD has replaced its renderer
		if (testObjects.isEmpty() && !recreatedSceneRenderer.hasContent()) {
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
			Object result;
			try {
				result = method.invoke(target, args);
			} catch (InvocationTargetException ex) {
				throw ex.getCause();
			}
			// Draw the recreated scene's zones after 117 HD's, without letting a problem with them stop 117 HD
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
		// Keep the scene position & plane bits, and replace the type & ID, which are what 117 HD reads
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
