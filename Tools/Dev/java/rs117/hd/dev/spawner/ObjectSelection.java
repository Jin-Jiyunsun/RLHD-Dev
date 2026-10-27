package rs117.hd.dev.spawner;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.event.MouseEvent;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import rs117.hd.dev.TestObjectRenderer;
import rs117.hd.renderer.zone.RecreatedSceneRenderer.StaticObject;
import rs117.hd.scene.GamevalManager;
import rs117.hd.utils.ModelHash;

import static rs117.hd.dev.Reflection.method;

/**
 * Right-clicking a spawned or recreated object selects it for the sidebar, outlined like Object Markers does. Clicks are
 * only watched and passed on unchanged, so the game's menu & clickboxes work exactly as without it.
 */
@Slf4j
@Singleton
public class ObjectSelection extends Overlay {
	private static final Color OUTLINE_COLOR = new Color(0x00E0FF);
	private static final int OUTLINE_WIDTH = 2;
	private static final int OUTLINE_FEATHER = 2;
	// The outline Object Markers draws for game objects, for models which aren't tile objects
	private static final Method DRAW_MODEL_OUTLINE = method(
		ModelOutlineRenderer.class,
		"drawModelOutline",
		WorldView.class, Model.class, int.class, int.class, int.class, int.class, int.class, Color.class, int.class
	);

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private EventBus eventBus;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ModelOutlineRenderer outlineRenderer;

	@Inject
	private GamevalManager gamevalManager;

	@Inject
	private TestObjectRenderer testObjectRenderer;

	@Inject
	private Provider<SceneTools> sceneTools;

	@Inject
	private Provider<SpawnerTools> spawnerTools;

	/** A selected object, either a spawned or animated test object, or a recreated static object or NPC */
	public static class Selected {
		// ModelHash type
		public final int type;
		public final int id;
		@Nullable
		public final String gameval;
		@Nullable
		private final RuneLiteObject testObject;
		@Nullable
		private final StaticObject staticObject;

		private Selected(int type, int id, @Nullable String gameval, @Nullable RuneLiteObject testObject, @Nullable StaticObject staticObject) {
			this.type = type;
			this.id = id;
			this.gameval = gameval;
			this.testObject = testObject;
			this.staticObject = staticObject;
		}
	}

	// Read while drawing the outlines
	private final List<Selected> selected = new CopyOnWriteArrayList<>();
	private boolean started;

	private final MouseAdapter rightClicks = new MouseAdapter() {
		@Override
		public MouseEvent mousePressed(MouseEvent event) {
			// Only watched, never consumed or changed, so the game still opens its menu
			if (SwingUtilities.isRightMouseButton(event)) {
				var mouse = client.getMouseCanvasPosition();
				if (mouse != null)
					clientThread.invoke(() -> select(mouse.getX(), mouse.getY()));
			}
			return event;
		}
	};

	ObjectSelection() {
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	public void startUp() {
		if (started)
			return;
		started = true;
		eventBus.register(this);
		mouseManager.registerMouseListener(rightClicks);
		overlayManager.add(this);
	}

	public void shutDown() {
		if (!started)
			return;
		started = false;
		mouseManager.unregisterMouseListener(rightClicks);
		overlayManager.remove(this);
		eventBus.unregister(this);
		clear();
	}

	public void remove(Selected object) {
		if (selected.remove(object))
			showInPanel();
	}

	public void clear() {
		selected.clear();
		showInPanel();
	}

	/** Select whichever spawned or recreated object is in front under the mouse, if any. Client thread only. */
	private void select(int mouseX, int mouseY) {
		var worldView = client.getTopLevelWorldView();
		if (!started || worldView == null || spawnerTools.get().getPanel() == null)
			return;

		RuneLiteObject nearestTestObject = null;
		StaticObject nearestStaticObject = null;
		long nearest = Long.MAX_VALUE;
		for (var object : testObjectRenderer.getTestObjects().keySet()) {
			var location = object.getLocation();
			if (!object.isActive() || location == null)
				continue;
			long distance = hitDistance(worldView, object.getModel(), object.getOrientation(),
				location.getX(), location.getY(), object.getZ(), mouseX, mouseY);
			if (distance < nearest) {
				nearest = distance;
				nearestTestObject = object;
			}
		}
		for (var object : sceneTools.get().getRecreatedStaticObjects()) {
			if (object.removed)
				continue;
			long distance = hitDistance(worldView, object.model, object.orientation, object.x, object.y, object.height, mouseX, mouseY);
			if (distance < nearest) {
				nearest = distance;
				nearestTestObject = null;
				nearestStaticObject = object;
			}
		}

		Selected picked = null;
		if (nearestTestObject != null) {
			Integer id = testObjectRenderer.getTestObjects().get(nearestTestObject);
			if (id != null)
				picked = create(ModelHash.TYPE_OBJECT, id, nearestTestObject, null);
		} else if (nearestStaticObject != null) {
			picked = create(nearestStaticObject.type, nearestStaticObject.id, null, nearestStaticObject);
		}
		if (picked == null || isSelected(picked))
			return;
		selected.add(picked);
		showInPanel();
	}

	private Selected create(int type, int id, @Nullable RuneLiteObject testObject, @Nullable StaticObject staticObject) {
		String gameval;
		try (var gamevals = gamevalManager.obtainHandle()) {
			gameval = type == ModelHash.TYPE_NPC ? gamevals.getNpcName(id) : gamevals.getObjectName(id);
		}
		return new Selected(type, id, gameval, testObject, staticObject);
	}

	private boolean isSelected(Selected picked) {
		for (var object : selected)
			if (object.testObject == picked.testObject && object.staticObject == picked.staticObject)
				return true;
		return false;
	}

	/**
	 * How far the camera is from a model under the mouse, or Long.MAX_VALUE if the mouse isn't over it, using the same
	 * clickbox as real objects. Its box on screen is checked first, since there can be thousands of recreated objects.
	 */
	private long hitDistance(WorldView worldView, @Nullable Model model, int orientation, int x, int y, int z, int mouseX, int mouseY) {
		if (model == null)
			return Long.MAX_VALUE;
		AABB box = model.getAABB(orientation);
		int centerX = x + box.getCenterX();
		int centerY = y + box.getCenterZ();
		int centerZ = z + box.getCenterY();
		if (!boxContains(centerX, centerY, centerZ, box.getExtremeX(), box.getExtremeZ(), box.getExtremeY(), mouseX, mouseY))
			return Long.MAX_VALUE;
		Shape clickbox = Perspective.getClickbox(client, worldView, model, orientation, x, y, z);
		if (clickbox == null || !clickbox.contains(mouseX, mouseY))
			return Long.MAX_VALUE;
		long dx = centerX - client.getCameraX();
		long dy = centerY - client.getCameraY();
		long dz = centerZ - client.getCameraZ();
		return dx * dx + dy * dy + dz * dz;
	}

	/** Whether the mouse is within the screen bounds of a box's corners, counting boxes partly behind the camera */
	private boolean boxContains(int x, int y, int z, int extentX, int extentY, int extentZ, int mouseX, int mouseY) {
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
		for (int corner = 0; corner < 8; corner++) {
			var point = Perspective.localToCanvas(
				client,
				x + ((corner & 1) == 0 ? -extentX : extentX),
				y + ((corner & 2) == 0 ? -extentY : extentY),
				z + ((corner & 4) == 0 ? -extentZ : extentZ)
			);
			if (point == null)
				return true;
			minX = Math.min(minX, point.getX());
			maxX = Math.max(maxX, point.getX());
			minY = Math.min(minY, point.getY());
			maxY = Math.max(maxY, point.getY());
		}
		return mouseX >= minX && mouseX <= maxX && mouseY >= minY && mouseY <= maxY;
	}

	@Subscribe
	public void onClientTick(ClientTick clientTick) {
		// Forget objects which have been removed
		if (selected.removeIf(object -> !exists(object)))
			showInPanel();
	}

	private boolean exists(Selected object) {
		if (object.testObject != null)
			return object.testObject.isActive() && testObjectRenderer.getTestObjects().containsKey(object.testObject);
		return object.staticObject != null && !object.staticObject.removed;
	}

	@Override
	public Dimension render(Graphics2D graphics) {
		var worldView = client.getTopLevelWorldView();
		if (worldView == null)
			return null;
		for (var object : selected) {
			if (object.testObject != null) {
				outlineRenderer.drawOutline(object.testObject, OUTLINE_WIDTH, OUTLINE_COLOR, OUTLINE_FEATHER);
			} else if (object.staticObject != null) {
				var s = object.staticObject;
				try {
					DRAW_MODEL_OUTLINE.invoke(outlineRenderer, worldView, s.model, s.x, s.y, s.height, s.orientation,
						OUTLINE_WIDTH, OUTLINE_COLOR, OUTLINE_FEATHER);
				} catch (IllegalAccessException | InvocationTargetException ex) {
					log.debug("Unable to outline recreated object {}", s.id, ex);
				}
			}
		}
		return null;
	}

	private void showInPanel() {
		var objects = new ArrayList<>(selected);
		SwingUtilities.invokeLater(() -> {
			var panel = spawnerTools.get().getPanel();
			if (panel != null)
				panel.setSelectedObjects(objects);
		});
	}
}
