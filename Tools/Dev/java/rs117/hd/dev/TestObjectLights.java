package rs117.hd.dev;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import rs117.hd.HdPlugin;
import rs117.hd.renderer.zone.RecreatedSceneRenderer.StaticObject;
import rs117.hd.scene.lights.Light;
import rs117.hd.scene.lights.LightDefinition;
import rs117.hd.utils.ModelHash;

import static rs117.hd.utils.MathUtils.*;

/**
 * Gives test & recreated objects the lights of the IDs they stand in for, added without a parent since 117 HD drops
 * lights of objects not in the scene. Kept in place here, and added again whenever 117 HD reloads its lights.
 */
@Singleton
public class TestObjectLights {
	@Inject
	private Client client;

	@Inject
	private EventBus eventBus;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdInternals hdInternals;

	@Inject
	private TestObjectRenderer testObjectRenderer;

	// May no longer be in 117 HD's list after it reloads its lights
	private final Map<Light, RuneLiteObject> testObjectLights = new IdentityHashMap<>();
	// Recreated static objects & NPCs, which never move
	private final Map<Light, StaticObject> staticObjectLights = new IdentityHashMap<>();
	private List<StaticObject> staticObjects = List.of();
	private Set<StaticObject> staticObjectSet = Set.of();

	public void startUp() {
		eventBus.register(this);
	}

	public void shutDown() {
		eventBus.unregister(this);
		removeAll();
	}

	/** Light a recreated scene's static objects & NPCs, fading out those lit before */
	public void setStaticObjects(List<StaticObject> objects) {
		staticObjects = objects;
		staticObjectSet = Collections.newSetFromMap(new IdentityHashMap<>());
		staticObjectSet.addAll(objects);
	}

	@Subscribe
	public void onClientTick(ClientTick clientTick) {
		if (plugin.getSceneContext() == null)
			return;

		var testObjects = testObjectRenderer.getTestObjects();
		// Nothing to light or fade out
		if (testObjects.isEmpty() && testObjectLights.isEmpty() && staticObjects.isEmpty() && staticObjectLights.isEmpty())
			return;

		var lights = hdInternals.getLights();
		updateLights(
			lights,
			testObjectLights,
			testObjects.keySet(),
			object -> object.isActive() && testObjects.containsKey(object),
			object -> testObjectRenderer.isNpc(object) ?
				hdInternals.getNpcLights(testObjects.get(object)) :
				hdInternals.getObjectLights(testObjects.get(object)),
			(light, object) -> {
				var location = object.getLocation();
				light.tileObjectId = testObjects.get(object);
				light.plane = object.getLevel();
				light.orientation = getLightOrientation(object);
				light.origin[0] = location.getX();
				// Keeps its own height instead of following the ground
				light.origin[1] = object.getZ() - light.def.height - 1;
				light.origin[2] = location.getY();
			}
		);

		var current = staticObjectSet;
		updateLights(
			lights,
			staticObjectLights,
			staticObjects,
			object -> !object.removed && current.contains(object),
			object -> object.type == ModelHash.TYPE_NPC ? hdInternals.getNpcLights(object.id) : hdInternals.getObjectLights(object.id),
			(light, object) -> {
				light.tileObjectId = object.id;
				light.plane = object.plane;
				light.orientation = object.lightOrientation;
				light.origin[0] = object.x;
				light.origin[1] = object.height - light.def.height - 1;
				light.origin[2] = object.y;
			}
		);
	}

	/** Keep each object lit while it exists, fading its lights out once it's gone */
	private static <T> void updateLights(
		List<Light> lights,
		Map<Light, T> objectLights,
		Iterable<T> objects,
		Predicate<T> exists,
		Function<T, List<LightDefinition>> definitions,
		BiConsumer<Light, T> place
	) {
		Set<T> lit = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<Light> inList = Collections.newSetFromMap(new IdentityHashMap<>());
		for (var light : lights) {
			var object = objectLights.get(light);
			if (object == null)
				continue;
			inList.add(light);
			if (light.markedForRemoval)
				continue;
			if (!exists.test(object)) {
				light.markedForRemoval = true;
				continue;
			}
			place.accept(light, object);
			lit.add(object);
		}
		// Forget lights 117 HD has dropped, whether faded out or cleared by a reload
		objectLights.keySet().retainAll(inList);

		for (var object : objects) {
			if (lit.contains(object) || !exists.test(object))
				continue;
			for (LightDefinition def : definitions.apply(object)) {
				Light light = new Light(def);
				place.accept(light, object);
				lights.add(light);
				objectLights.put(light, object);
			}
		}
	}

	private void removeAll() {
		if (client.isClientThread())
			hdInternals.getLights().removeIf(light -> testObjectLights.containsKey(light) || staticObjectLights.containsKey(light));
		testObjectLights.clear();
		staticObjectLights.clear();
		staticObjects = List.of();
		staticObjectSet = Set.of();
	}

	/** Real objects' lights face 1024 + 512 * rotation */
	private static int getLightOrientation(RuneLiteObject object) {
		return mod(1024 + object.getOrientation(), 2048);
	}
}
