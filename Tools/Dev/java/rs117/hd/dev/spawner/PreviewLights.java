package rs117.hd.dev.spawner;

import com.google.common.collect.ListMultimap;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import rs117.hd.dev.HdInternals;
import rs117.hd.scene.lights.LightDefinition;

/**
 * 117 HD lights a spotanim on an actor only by lights.json's graphicsObjectIds, and a projectile only by its projectileIds,
 * so a gameval lit under the other kind gets no light in the spawner's preview. While a spotanim or projectile is being
 * previewed, its ID gets the other kind's lights too, taken away again once the preview stops. Client thread only, where
 * 117 HD uses these lights.
 */
@Singleton
class PreviewLights {
	@Inject
	private HdInternals hdInternals;

	// The clicked spotanim and the hover preview, or -1
	private int[] spotanimIds = { -1, -1 };
	private int projectileId = -1;
	// What's been added, to take away again
	private final List<Added> added = new ArrayList<>();

	private static class Added {
		final ListMultimap<Integer, LightDefinition> lights;
		final int id;
		final LightDefinition def;

		Added(ListMultimap<Integer, LightDefinition> lights, int id, LightDefinition def) {
			this.lights = lights;
			this.id = id;
			this.def = def;
		}
	}

	void setSpotanims(int clickedId, int previewId) {
		if (spotanimIds[0] == clickedId && spotanimIds[1] == previewId)
			return;
		spotanimIds = new int[] { clickedId, previewId };
		refresh();
	}

	void setProjectile(int id) {
		if (id == projectileId)
			return;
		projectileId = id;
		refresh();
	}

	void clear() {
		spotanimIds = new int[] { -1, -1 };
		projectileId = -1;
		refresh();
	}

	/** Add any that are missing, such as after 117 HD reloads lights.json. Call every tick. */
	void update() {
		for (int id : spotanimIds)
			if (id != -1)
				alias(hdInternals.getProjectileLightTable(), hdInternals.getGraphicsObjectLightTable(), id);
		if (projectileId != -1)
			alias(hdInternals.getGraphicsObjectLightTable(), hdInternals.getProjectileLightTable(), projectileId);
	}

	private void refresh() {
		for (var entry : added)
			entry.lights.remove(entry.id, entry.def);
		added.clear();
		update();
	}

	private void alias(ListMultimap<Integer, LightDefinition> from, ListMultimap<Integer, LightDefinition> to, int id) {
		for (var def : from.get(id)) {
			var existing = to.get(id);
			if (existing.contains(def))
				continue;
			existing.add(def);
			added.add(new Added(to, id, def));
		}
	}
}
