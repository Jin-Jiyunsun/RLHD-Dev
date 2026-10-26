package rs117.hd.dev;

import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.LightManager;
import rs117.hd.scene.ModelOverrideManager;
import rs117.hd.scene.environments.Environment;
import rs117.hd.scene.lights.Light;
import rs117.hd.scene.lights.LightDefinition;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.ResourcePath;
import rs117.hd.utils.collections.Int2ObjectHashMap;

import static rs117.hd.dev.Reflection.field;
import static rs117.hd.dev.Reflection.get;

/**
 * Reads and changes 117 HD's private state which the developer tools need, so 117 HD doesn't have to expose it.
 * Everything here except {@link #readGamevals} must be called on the client thread, where 117 HD changes this state.
 */
@Singleton
public class HdInternals {
	public static final String GAMEVAL_OBJECTS = "objects";
	public static final String GAMEVAL_SPOTANIMS = "spotanims";

	private static final Field LIGHTS = field(LightManager.class, "lights");
	private static final Field RELOAD_LIGHTS = field(LightManager.class, "reloadLights");
	private static final Field NPC_LIGHTS = field(LightManager.class, "NPC_LIGHTS");
	private static final Field OBJECT_LIGHTS = field(LightManager.class, "OBJECT_LIGHTS");
	private static final Field GRAPHICS_OBJECT_LIGHTS = field(LightManager.class, "GRAPHICS_OBJECT_LIGHTS");
	private static final Field PROJECTILE_LIGHTS = field(LightManager.class, "PROJECTILE_LIGHTS");
	private static final Field MODEL_OVERRIDES = field(ModelOverrideManager.class, "modelOverrides");
	private static final Field GAMEVAL_PATH = field(GamevalManager.class, "GAMEVAL_PATH");
	private static final Field ENVIRONMENTS = field(EnvironmentManager.class, "environments");

	@Inject
	private Client client;

	@Inject
	private Gson gson;

	@Inject
	private LightManager lightManager;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	@Inject
	private EnvironmentManager environmentManager;

	@SuppressWarnings("unchecked")
	public List<Light> getLights() {
		assert client.isClientThread();
		return (List<Light>) get(LIGHTS, lightManager);
	}

	public Set<Integer> getObjectLightIds() {
		return getLightIds(OBJECT_LIGHTS);
	}

	public Set<Integer> getGraphicsObjectLightIds() {
		return getLightIds(GRAPHICS_OBJECT_LIGHTS);
	}

	public Set<Integer> getProjectileLightIds() {
		return getLightIds(PROJECTILE_LIGHTS);
	}

	@SuppressWarnings("unchecked")
	public List<LightDefinition> getObjectLights(int objectId) {
		assert client.isClientThread();
		return ((ListMultimap<Integer, LightDefinition>) get(OBJECT_LIGHTS, lightManager)).get(objectId);
	}

	@SuppressWarnings("unchecked")
	public List<LightDefinition> getNpcLights(int npcId) {
		assert client.isClientThread();
		return ((ListMultimap<Integer, LightDefinition>) get(NPC_LIGHTS, lightManager)).get(npcId);
	}

	/**
	 * Have 117 HD load the scene's lights again before it next updates them, like it does after lights.json changes
	 */
	public void reloadLights() {
		assert client.isClientThread();
		try {
			RELOAD_LIGHTS.setBoolean(lightManager, true);
		} catch (IllegalAccessException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * The environments.json environment with this name, as 117 HD has it loaded now, or null if there's none
	 */
	@Nullable
	public Environment findEnvironment(String name) {
		assert client.isClientThread();
		if (name.equals(Environment.DEFAULT.toString()))
			return Environment.DEFAULT;
		for (var environment : (Environment[]) get(ENVIRONMENTS, environmentManager))
			if (name.equals(environment.toString()))
				return environment;
		return null;
	}

	private Set<Integer> getLightIds(Field field) {
		assert client.isClientThread();
		return new HashSet<>(((Multimap<Integer, ?>) get(field, lightManager)).keySet());
	}

	/**
	 * The IDs of the given ModelHash type which have model overrides
	 */
	public Set<Integer> getOverriddenIds(int type) {
		assert client.isClientThread();
		HashSet<Integer> ids = new HashSet<>();
		for (var entry : (Int2ObjectHashMap<?>) get(MODEL_OVERRIDES, modelOverrideManager))
			if (ModelHash.getUuidType(entry.getKey()) == type)
				ids.add(ModelHash.getUuidId(entry.getKey()));
		return ids;
	}

	/**
	 * Remove an actor's spotanim lights without fading them out, so a restarted spotanim gets fresh lights
	 */
	public void removeSpotanimLightsImmediately(Actor actor, int spotanimId) {
		getLights().removeIf(light -> light.actor == actor && light.spotanimId == spotanimId);
	}

	/**
	 * Read one category of 117 HD's gamevals, separately from the copy 117 HD keeps. Safe to call from any thread,
	 * so the disk IO can stay off the client thread.
	 */
	public Map<String, Integer> readGamevals(String category) throws IOException {
		try (var reader = ((ResourcePath) get(GAMEVAL_PATH, null)).toReader()) {
			Map<String, Map<String, Integer>> gamevals = gson.fromJson(
				reader,
				new TypeToken<Map<String, Map<String, Integer>>>() {}.getType()
			);
			return gamevals.getOrDefault(category, Collections.emptyMap());
		}
	}
}
