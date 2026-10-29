package rs117.hd.dev.spawner;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.event.MouseEvent;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.model.Jarvis;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import rs117.hd.dev.TestObjectRenderer;
import rs117.hd.renderer.zone.RecreatedSceneRenderer;
import rs117.hd.renderer.zone.RecreatedSceneRenderer.StaticObject;
import rs117.hd.scene.GamevalManager;
import rs117.hd.utils.ModelHash;

import static rs117.hd.dev.Reflection.method;

/**
 * Right-clicking a spawned or recreated object selects it for the sidebar, outlined like Object Markers does. Clicks are
 * only watched and passed on unchanged, so the game's menu & clickboxes work exactly as without it. Nothing is consumed,
 * no menu entries are added and nothing is sent to the server: it only reads the mouse position and draws on top.
 */
@Slf4j
@Singleton
public class ObjectSelection extends Overlay {
	// Also the sidebar's numbers, to match them up
	static final Color OUTLINE_COLOR = new Color(0x00E0FF);
	private static final int OUTLINE_WIDTH = 2;
	private static final int OUTLINE_FEATHER = 2;
	private static final BasicStroke CLICKBOX_STROKE = new BasicStroke(OUTLINE_WIDTH);
	// Object Markers' outline, which RuneLite only exposes for tile objects, actors & RuneLiteObjects
	private static final Method DRAW_MODEL_OUTLINE = method(
		ModelOutlineRenderer.class,
		"drawModelOutline",
		WorldView.class, Model.class, int.class, int.class, int.class, int.class, int.class, Color.class, int.class
	);
	// Pixels between the top of a selected object & its number
	private static final int NUMBER_GAP = 6;
	// Screen sizes away from the viewport, beyond which a projected corner is treated as behind the camera
	private static final int OFF_SCREEN_REACH = 4;

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
	private RecreatedSceneRenderer recreatedSceneRenderer;

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
	// Recreated objects don't move or change model, so their extents are worked out once
	private final Map<StaticObject, int[]> staticBounds = new WeakHashMap<>();
	// Recreated objects whose models can't be outlined, such as captured roofs' stand-ins, outlined by their hull instead
	private final Set<StaticObject> hullOutlined = Collections.newSetFromMap(new WeakHashMap<>());
	// Projected vertices, reused between models
	private int[] screenX = new int[0];
	private int[] screenY = new int[0];

	private final MouseAdapter rightClicks = new MouseAdapter() {
		@Override
		public MouseEvent mousePressed(MouseEvent event) {
			// Only watched, never consumed or changed, so the game still opens its menu. Already in canvas space, even when
			// stretched, and read now since the client hasn't seen this press yet.
			if (SwingUtilities.isRightMouseButton(event)) {
				int mouseX = event.getX();
				int mouseY = event.getY();
				clientThread.invokeLater(() -> select(mouseX, mouseY));
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
			long distance;
			try {
				distance = hitDistance(worldView, object.getModel(), object.getOrientation(),
					location.getX(), location.getY(), object.getZ(), null, mouseX, mouseY);
			} catch (RuntimeException ex) {
				// One odd model mustn't stop the rest being picked
				log.debug("Unable to hit test spawned object", ex);
				continue;
			}
			if (distance < nearest) {
				nearest = distance;
				nearestTestObject = object;
			}
		}
		for (var object : sceneTools.get().getRecreatedStaticObjects()) {
			// Objects still waiting to be uploaded aren't drawn yet, so they can't be what was clicked
			if (object.removed || !recreatedSceneRenderer.isDrawn(object))
				continue;
			long distance;
			try {
				int[] bounds = object.model == null ? null : staticBounds.computeIfAbsent(object, o -> getBounds(o.model, o.orientation));
				distance = hitDistance(worldView, object.model, object.orientation, object.x, object.y, object.height, bounds, mouseX, mouseY);
			} catch (RuntimeException ex) {
				log.debug("Unable to hit test recreated object {}", object.id, ex);
				continue;
			}
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
				picked = create(
					testObjectRenderer.isNpc(nearestTestObject) ? ModelHash.TYPE_NPC : ModelHash.TYPE_OBJECT,
					id,
					nearestTestObject,
					null
				);
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
	 * How far the camera is from a model under the mouse, or Long.MAX_VALUE if the mouse isn't over any of its triangles.
	 * Worked out from the model's own vertices, since captured roofs' stand-in models have no AABB for getClickbox. Its
	 * box on screen is checked first, since there can be thousands of recreated objects.
	 */
	private long hitDistance(
		WorldView worldView, @Nullable Model model, int orientation, int x, int y, int z, @Nullable int[] bounds, int mouseX, int mouseY
	) {
		if (model == null || model.getVerticesCount() == 0)
			return Long.MAX_VALUE;
		if (bounds == null)
			bounds = getBounds(model, orientation);
		if (!boxContains(x, y, z, bounds, mouseX, mouseY) || !trianglesContain(worldView, model, orientation, x, y, z, mouseX, mouseY))
			return Long.MAX_VALUE;
		long dx = x + (bounds[0] + bounds[1]) / 2 - client.getCameraX();
		long dy = y + (bounds[2] + bounds[3]) / 2 - client.getCameraY();
		long dz = z + (bounds[4] + bounds[5]) / 2 - client.getCameraZ();
		return dx * dx + dy * dy + dz * dz;
	}

	/** A model's extent around its position once turned: min & max local x, then local y, then height */
	private static int[] getBounds(Model model, int orientation) {
		float sin = Perspective.SINE[orientation] / 65536f;
		float cos = Perspective.COSINE[orientation] / 65536f;
		float[] vx = model.getVerticesX();
		float[] vy = model.getVerticesY();
		float[] vz = model.getVerticesZ();
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
		float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
		for (int i = 0; i < model.getVerticesCount(); i++) {
			// Turned the same way Perspective.modelToCanvas turns them
			float x = vx[i] * cos + vz[i] * sin;
			float y = vz[i] * cos - vx[i] * sin;
			minX = Math.min(minX, x);
			maxX = Math.max(maxX, x);
			minY = Math.min(minY, y);
			maxY = Math.max(maxY, y);
			minZ = Math.min(minZ, vy[i]);
			maxZ = Math.max(maxZ, vy[i]);
		}
		return new int[] {
			(int) Math.floor(minX), (int) Math.ceil(maxX),
			(int) Math.floor(minY), (int) Math.ceil(maxY),
			(int) Math.floor(minZ), (int) Math.ceil(maxZ)
		};
	}

	/** Whether the mouse is within the screen bounds of a box's corners, counting boxes partly behind the camera */
	private boolean boxContains(int x, int y, int z, int[] bounds, int mouseX, int mouseY) {
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
		for (int corner = 0; corner < 8; corner++) {
			var point = Perspective.localToCanvas(
				client,
				x + bounds[corner & 1],
				y + bounds[2 + (corner >> 1 & 1)],
				z + bounds[4 + (corner >> 2 & 1)]
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

	/** Whether the mouse is over one of a model's drawn triangles, like the client's own clickboxes */
	private boolean trianglesContain(WorldView worldView, Model model, int orientation, int x, int y, int z, int mouseX, int mouseY) {
		int vertices = projectVertices(worldView, model, orientation, x, y, z);
		// Corners barely in front of the camera project far off screen, stretching their triangles over everything, so
		// they're left out like those behind it. No triangle on screen reaches this far.
		int reach = OFF_SCREEN_REACH * Math.max(client.getViewportWidth(), client.getViewportHeight());
		int[] faceA = model.getFaceIndices1();
		int[] faceB = model.getFaceIndices2();
		int[] faceC = model.getFaceIndices3();
		int[] faceColors3 = model.getFaceColors3();
		for (int face = 0; face < model.getFaceCount(); face++) {
			// Hidden faces
			if (faceColors3 != null && faceColors3[face] == -2)
				continue;
			int a = faceA[face], b = faceB[face], c = faceC[face];
			if (a >= vertices || b >= vertices || c >= vertices)
				continue;
			// Behind the camera
			if (screenX[a] == Integer.MIN_VALUE || screenX[b] == Integer.MIN_VALUE || screenX[c] == Integer.MIN_VALUE)
				continue;
			if (isFarOffScreen(a, reach) || isFarOffScreen(b, reach) || isFarOffScreen(c, reach))
				continue;
			if (triangleContains(screenX[a], screenY[a], screenX[b], screenY[b], screenX[c], screenY[c], mouseX, mouseY))
				return true;
		}
		return false;
	}

	/** Project a model's vertices into screenX & screenY, returning how many */
	private boolean isFarOffScreen(int vertex, int reach) {
		return Math.abs(screenX[vertex]) > reach || Math.abs(screenY[vertex]) > reach;
	}

	private int projectVertices(WorldView worldView, Model model, int orientation, int x, int y, int z) {
		int vertices = model.getVerticesCount();
		if (screenX.length < vertices) {
			screenX = new int[vertices];
			screenY = new int[vertices];
		}
		Perspective.modelToCanvas(client, worldView, vertices, x, y, z, orientation,
			model.getVerticesX(), model.getVerticesZ(), model.getVerticesY(), screenX, screenY);
		return vertices;
	}

	private static boolean triangleContains(int ax, int ay, int bx, int by, int cx, int cy, int px, int py) {
		long ab = (long) (bx - ax) * (py - ay) - (long) (by - ay) * (px - ax);
		long bc = (long) (cx - bx) * (py - by) - (long) (cy - by) * (px - bx);
		long ca = (long) (ax - cx) * (py - cy) - (long) (ay - cy) * (px - cx);
		return ab >= 0 && bc >= 0 && ca >= 0 || ab <= 0 && bc <= 0 && ca <= 0;
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
		graphics.setFont(FontManager.getRunescapeBoldFont());
		int number = 0;
		for (var object : selected) {
			number++;
			if (object.testObject != null) {
				var t = object.testObject;
				var location = t.getLocation();
				outlineRenderer.drawOutline(t, OUTLINE_WIDTH, OUTLINE_COLOR, OUTLINE_FEATHER);
				if (location != null && t.getModel() != null)
					drawNumber(graphics, number, worldView, t.getModel(), t.getOrientation(), location.getX(), location.getY(), t.getZ());
			} else if (object.staticObject != null && object.staticObject.model != null) {
				var s = object.staticObject;
				if (!hullOutlined.contains(s) && !drawModelOutline(worldView, s))
					hullOutlined.add(s);
				if (hullOutlined.contains(s)) {
					Shape hull = getHull(worldView, s.model, s.orientation, s.x, s.y, s.height);
					if (hull != null) {
						graphics.setColor(OUTLINE_COLOR);
						graphics.setStroke(CLICKBOX_STROKE);
						graphics.draw(hull);
					}
				}
				drawNumber(graphics, number, worldView, s.model, s.orientation, s.x, s.y, s.height);
			}
		}
		return null;
	}

	/** Outline a recreated object exactly like Object Markers, returning false if its model can't be */
	private boolean drawModelOutline(WorldView worldView, StaticObject s) {
		try {
			DRAW_MODEL_OUTLINE.invoke(outlineRenderer, worldView, s.model, s.x, s.y, s.height, s.orientation,
				OUTLINE_WIDTH, OUTLINE_COLOR, OUTLINE_FEATHER);
			return true;
		} catch (IllegalAccessException | InvocationTargetException | RuntimeException ex) {
			log.debug("Outlining recreated object {} by its hull instead", s.id, ex);
			return false;
		}
	}

	/** Its number in the sidebar, centred just above the highest point of it on screen, clear of the outline */
	private void drawNumber(Graphics2D graphics, int number, WorldView worldView, Model model, int orientation, int x, int y, int z) {
		int vertices = projectVertices(worldView, model, orientation, x, y, z);
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE;
		for (int i = 0; i < vertices; i++) {
			if (screenX[i] == Integer.MIN_VALUE)
				continue;
			minX = Math.min(minX, screenX[i]);
			maxX = Math.max(maxX, screenX[i]);
			minY = Math.min(minY, screenY[i]);
		}
		if (minY == Integer.MAX_VALUE)
			return;
		String text = String.valueOf(number);
		var location = new net.runelite.api.Point((minX + maxX) / 2 - graphics.getFontMetrics().stringWidth(text) / 2, minY - NUMBER_GAP);
		OverlayUtil.renderTextLocation(graphics, location, text, OUTLINE_COLOR);
	}

	@Nullable
	private Shape getHull(WorldView worldView, Model model, int orientation, int x, int y, int z) {
		int vertices = projectVertices(worldView, model, orientation, x, y, z);
		int[] xs = new int[vertices];
		int[] ys = new int[vertices];
		int visible = 0;
		for (int i = 0; i < vertices; i++) {
			if (screenX[i] == Integer.MIN_VALUE)
				continue;
			xs[visible] = screenX[i];
			ys[visible++] = screenY[i];
		}
		return visible < 3 ? null : Jarvis.convexHull(Arrays.copyOf(xs, visible), Arrays.copyOf(ys, visible));
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
