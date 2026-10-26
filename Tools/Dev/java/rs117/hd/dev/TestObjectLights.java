package rs117.hd.dev;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Gives test objects, and the objects & NPCs of a recreated scene capture, the lights.json lights of the IDs they stand in for.
 * <p>
 * 117 HD removes an object's lights as soon as the object isn't in the scene, which test objects never are. So instead,
 * their lights are added to 117 HD without a parent, which 117 HD keeps until they're marked for removal. They're kept
 * facing the same way as their object here, and added again whenever 117 HD reloads its lights.
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

	// The lights given to test objects, which may no longer be in 117 HD's list if it has reloaded its lights since
	private final Map<Light, RuneLiteObject> testObjectLights = new IdentityHashMap<>();
	// The same for a recreated scene's static objects & NPCs, which never move
	private final Map<Light, StaticObject> staticObjectLights = new IdentityHashMap<>();
	private List<StaticObject> staticObjects = List.of();

	public void startUp() {
		eventBus.register(this);
	}

	public void shutDown() {
		eventBus.unregister(this);
		removeAll();
	}

	/**
	 * Light a recreated scene's static objects & NPCs, replacing those lit before, whose lights fade out
	 */
	public void setStaticObjects(List<StaticObject> objects) {
		staticObjects = objects;
	}

	@Subscribe
	public void onClientTick(ClientTick clientTick) {
		if (plugin.getSceneContext() == null)
			return;

		var testObjects = testObjectRenderer.getTestObjects();
		var lights = hdInternals.getLights();
		Set<RuneLiteObject> lit = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<Light> current = Collections.newSetFromMap(new IdentityHashMap<>());
		for (var light : lights) {
			var object = testObjectLights.get(light);
			if (object == null)
				continue;
			current.add(light);
			if (light.markedForRemoval)
				continue;
			if (!object.isActive() || !testObjects.containsKey(object)) {
				// Fade out with the object, like real objects' lights
				light.markedForRemoval = true;
				continue;
			}
			light.orientation = getLightOrientation(object);
			lit.add(object);
		}
		// Forget lights 117 HD has dropped, whether faded out or cleared by a reload
		testObjectLights.keySet().retainAll(current);

		for (var entry : testObjects.entrySet()) {
			var object = entry.getKey();
			if (!object.isActive() || lit.contains(object))
				continue;
			// Most objects have no lights
			var defs = hdInternals.getObjectLights(entry.getValue());
			if (defs.isEmpty())
				continue;

			var location = object.getLocation();
			for (LightDefinition def : defs) {
				Light light = new Light(def);
				light.tileObjectId = entry.getValue();
				light.plane = object.getLevel();
				light.orientation = getLightOrientation(object);
				light.origin[0] = location.getX();
				// The object's own height, which it keeps instead of following the ground
				light.origin[1] = object.getZ() - def.height - 1;
				light.origin[2] = location.getY();
				lights.add(light);
				testObjectLights.put(light, object);
			}
		}

		updateStaticObjectLights(lights);
	}

	private void updateStaticObjectLights(List<Light> lights) {
		Set<StaticObject> lit = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<StaticObject> current = Collections.newSetFromMap(new IdentityHashMap<>());
		current.addAll(staticObjects);
		Set<Light> inList = Collections.newSetFromMap(new IdentityHashMap<>());
		for (var light : lights) {
			var object = staticObjectLights.get(light);
			if (object == null)
				continue;
			inList.add(light);
			if (light.markedForRemoval)
				continue;
			if (object.removed || !current.contains(object)) {
				light.markedForRemoval = true;
				continue;
			}
			lit.add(object);
		}
		staticObjectLights.keySet().retainAll(inList);

		for (var object : staticObjects) {
			if (object.removed || lit.contains(object))
				continue;
			var defs = object.type == ModelHash.TYPE_NPC ? hdInternals.getNpcLights(object.id) : hdInternals.getObjectLights(object.id);
			for (LightDefinition def : defs) {
				Light light = new Light(def);
				light.tileObjectId = object.id;
				light.plane = object.plane;
				light.orientation = object.lightOrientation;
				light.origin[0] = object.x;
				light.origin[1] = object.height - def.height - 1;
				light.origin[2] = object.y;
				lights.add(light);
				staticObjectLights.put(light, object);
			}
		}
	}

	private void removeAll() {
		if (client.isClientThread())
			hdInternals.getLights().removeIf(light -> testObjectLights.containsKey(light) || staticObjectLights.containsKey(light));
		testObjectLights.clear();
		staticObjectLights.clear();
		staticObjects = List.of();
	}

	/**
	 * Real objects' lights are oriented 1024 + 512 * rotation, so match that for test objects rotated in 90 degree steps
	 */
	private static int getLightOrientation(RuneLiteObject object) {
		return mod(1024 + object.getOrientation(), 2048);
	}
}
