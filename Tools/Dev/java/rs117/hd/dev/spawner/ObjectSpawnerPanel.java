package rs117.hd.dev.spawner;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.swing.AbstractButton;
import javax.swing.AbstractListModel;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.Scrollable;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.Border;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;

/**
 * Developer tools sidebar for searching objects, spotanims & projectiles, and spawning them purely visually on the client.
 */
public class ObjectSpawnerPanel extends PluginPanel {
	private static final int MAX_RESULTS = 500;
	// The cache doesn't mark which spotanims are projectiles, so besides those HD already configures as projectiles,
	// guess from names, leaving out effects which play when casting or hitting rather than in flight
	private static final Pattern PROJECTILE_NAME = Pattern.compile("PROJ|MISSILE|_TRAVEL|_ARROW|BOLT|DART");
	private static final Pattern NOT_PROJECTILE_NAME = Pattern.compile("LAUNCH|CAST|IMPACT|_HIT|HIT_|DEATH|EXPLO|SPLASH|_END\\b|_END_");
	// Always list entries in the order they're in the cache
	private static final Comparator<Entry> BY_ID = Comparator.comparingInt(entry -> entry.id);
	private static final int DEFAULT_PROJECTILE_DISTANCE = 10;
	private static final int DEFAULT_PROJECTILE_START_HEIGHT = 150;
	private static final int SPAWNED_ROW_HEIGHT = 32;
	private static final int SPAWNED_VISIBLE_ROWS = 5;
	private static final int SAVED_CAPTURES_VISIBLE_ROWS = 10;
	// Room above and below the line dividing sections, the same in every tab
	private static final int SECTION_GAP = 10;
	private static final Color SIDEBAR_ICON_COLOR = new Color(0xDC8A00);
	private static final int FLAG_ICON_SIZE = 10;
	private static final Color OVERRIDE_FLAG_COLOR = new Color(0x4CC94C);
	private static final ImageIcon OVERRIDE_FLAG_ICON = new ImageIcon(createCubeIcon(FLAG_ICON_SIZE, OVERRIDE_FLAG_COLOR));
	private static final ImageIcon LIGHTS_FLAG_ICON = new ImageIcon(createLightbulbIcon(FLAG_ICON_SIZE));
	private static final int FILTER_ICON_SIZE = 16;
	// Long names on the hovered row scroll at this many pixels per second, pausing at each end
	private static final int MARQUEE_SPEED = 40;
	private static final int MARQUEE_PAUSE_MS = 1000;
	private static final int MARQUEE_FRAME_MS = 33;

	private enum Mode {
		OBJECTS("Objects", "spawn", "Clear spawned objects"),
		SPOTANIMS("Spotanims", "play", "Stop spotanim"),
		PROJECTILES("Projectiles", "fire", "Stop projectile"),
		SCENES("Scenes", "recreate", "Remove recreated scene");

		final String label;
		final String action;
		final String clearLabel;

		Mode(String label, String action, String clearLabel) {
			this.label = label;
			this.action = action;
			this.clearLabel = clearLabel;
		}
	}

	private enum Filter {
		ALL,
		HAS_LIGHTS,
		HAS_OVERRIDE
	}

	private final SpawnerTools spawner;
	private final SceneTools scenes;
	private final JButton[] modeButtons = new JButton[Mode.values().length];
	private final IconTextField searchBar = new IconTextField();
	private final JToggleButton lightsFilterButton = new JToggleButton(new ImageIcon(createLightbulbIcon(FILTER_ICON_SIZE)));
	private final JToggleButton overrideFilterButton = new JToggleButton(new ImageIcon(createCubeIcon(FILTER_ICON_SIZE, OVERRIDE_FLAG_COLOR)));
	private final JButton loopButton = new JButton();
	private final JButton frameTimingsButton = new JButton();
	private final JSpinner distanceSpinner = new JSpinner(new SpinnerNumberModel(DEFAULT_PROJECTILE_DISTANCE, 1, 30, 1));
	private final JPanel distanceRow = new JPanel(new BorderLayout(6, 0));
	private final JSpinner startHeightSpinner = new JSpinner(new SpinnerNumberModel(DEFAULT_PROJECTILE_START_HEIGHT, 0, 2000, 10));
	private final JPanel startHeightRow = new JPanel(new BorderLayout(6, 0));
	private final JTextField startFromField = new JTextField();
	private final JButton clearButton = new JButton();
	// Two rows of tabs, since four don't fit across the narrow sidebar
	private final JPanel modeRow = new JPanel(new GridLayout(0, 2, 4, 4));
	private final JPanel filterRow = new JPanel(new GridLayout(1, 2, 4, 0));
	private final JPanel controls = new JPanel(new GridLayout(0, 1, 0, 4));
	private final JTextArea statusText = createWrappingText();
	private final JTextArea projectileDisclaimer = createWrappingText();
	private final JPanel spawnedSection = new JPanel(new BorderLayout(0, 4));
	// Keeps the status & spawned objects outside the grid, so they can grow beyond a single grid row
	private final JPanel topSection = new JPanel(new BorderLayout(0, 6));
	private final JPanel spawnedRows = new SpawnedRowsPanel();
	private final JLabel listHeader = createHeader("");
	private final JPanel listSection = new JPanel(new BorderLayout(0, 4));
	private final JPanel scenesSection = new JPanel(new BorderLayout(0, 6));
	private final JPanel recreatedSceneColumn = new JPanel(new GridLayout(0, 1, 0, 4));
	private final JTextField sceneNameField = new JTextField();
	private final JList<String> sceneCaptureList = new JList<>() {
		// Fit the sidebar's width, cutting long names off rather than widening it
		@Override
		public boolean getScrollableTracksViewportWidth() {
			return true;
		}
	};
	private final JButton recreateButton = createButton("Recreate scene");
	private final JToggleButton onlyTestObjectsButton = new JToggleButton("Hide everything else");
	private final ResultsModel results = new ResultsModel();
	private final JList<Entry> resultList = new JList<>(results) {
		// Always fit the sidebar's width, otherwise one long name widens every row and pushes the flags out of view
		@Override
		public boolean getScrollableTracksViewportWidth() {
			return true;
		}
	};

	private Mode mode = Mode.OBJECTS;
	private Filter filter = Filter.ALL;
	private List<Entry> objectEntries = List.of();
	private List<Entry> spotanimEntries = List.of();
	private List<Entry> projectileEntries = List.of();
	private int hoveredId = -1;
	private int marqueeIndex = -1;
	private long marqueeStartMs;
	private final Timer marqueeTimer = new Timer(MARQUEE_FRAME_MS, e -> repaintRow(marqueeIndex));
	// The spawned objects are real components rather than list rows, so their names animate separately
	@Nullable
	private MarqueeLabel spawnedMarqueeLabel;
	private final Timer spawnedMarqueeTimer = new Timer(MARQUEE_FRAME_MS, e -> {
		if (spawnedMarqueeLabel != null)
			spawnedMarqueeLabel.repaint();
	});
	@Nullable
	private Map<Integer, TestObjectDefinition> loadedDefinitions;
	@Nullable
	private SpawnerTools.HdConfigIds hdConfigIds;
	private boolean definitionsChecked;
	private boolean spotanimsLoaded;
	private boolean loop = true;
	private boolean frameTimings;

	public ObjectSpawnerPanel(SpawnerTools spawner, SceneTools scenes) {
		super(false);
		this.spawner = spawner;
		this.scenes = scenes;

		setLayout(new BorderLayout(0, 6));
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		modeRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		for (Mode m : Mode.values()) {
			JButton button = createButton(m.label);
			// Fit the labels across the narrow sidebar
			button.setMargin(new Insets(2, 0, 2, 0));
			button.addActionListener(e -> setMode(m));
			modeButtons[m.ordinal()] = button;
			modeRow.add(button);
		}

		searchBar.setIcon(IconTextField.Icon.SEARCH);
		searchBar.setPreferredSize(new Dimension(PANEL_WIDTH - 20, 30));
		searchBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchBar.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		searchBar.getDocument().addDocumentListener(onDocumentChange(this::updateResults));

		// Only one filter can be on at a time, and clicking the active one turns it off again
		filterRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		styleButton(lightsFilterButton);
		styleButton(overrideFilterButton);
		lightsFilterButton.setToolTipText("Only show entries with lights in lights.json");
		overrideFilterButton.setToolTipText("Only show entries with model overrides in model_overrides.json");
		lightsFilterButton.addActionListener(e -> setFilter(filter == Filter.HAS_LIGHTS ? Filter.ALL : Filter.HAS_LIGHTS));
		overrideFilterButton.addActionListener(e -> setFilter(filter == Filter.HAS_OVERRIDE ? Filter.ALL : Filter.HAS_OVERRIDE));
		filterRow.add(overrideFilterButton);
		filterRow.add(lightsFilterButton);

		styleButton(loopButton);
		loopButton.addActionListener(e -> {
			loop = !loop;
			updateLoopButton();
			if (mode == Mode.SPOTANIMS) {
				spawner.setSpotanimLoop(loop);
			} else if (mode == Mode.PROJECTILES) {
				spawner.setProjectileLoop(loop);
			}
		});
		updateLoopButton();

		styleButton(frameTimingsButton);
		frameTimingsButton.setToolTipText("Post a summary of each spotanim's frame timings in the chat after it first plays");
		frameTimingsButton.addActionListener(e -> {
			frameTimings = !frameTimings;
			updateFrameTimingsButton();
			spawner.setPostSpotanimFrameTimings(frameTimings);
		});
		updateFrameTimingsButton();

		JLabel distanceLabel = createLabel("Tiles ahead");
		distanceRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		distanceRow.add(distanceLabel, BorderLayout.CENTER);
		distanceRow.add(distanceSpinner, BorderLayout.EAST);
		distanceSpinner.addChangeListener(e -> spawner.setProjectileDistance((Integer) distanceSpinner.getValue()));
		spawner.setProjectileDistance(DEFAULT_PROJECTILE_DISTANCE);

		JLabel startHeightLabel = createLabel("Start height");
		startHeightRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		startHeightRow.add(startHeightLabel, BorderLayout.CENTER);
		startHeightRow.add(startHeightSpinner, BorderLayout.EAST);
		startHeightSpinner.addChangeListener(e -> spawner.setProjectileStartHeight((Integer) startHeightSpinner.getValue()));
		spawner.setProjectileStartHeight(DEFAULT_PROJECTILE_START_HEIGHT);

		// Line the number boxes up with each other
		distanceSpinner.setPreferredSize(startHeightSpinner.getPreferredSize());

		styleButton(clearButton);
		clearButton.addActionListener(e -> {
			switch (mode) {
				case OBJECTS:
					spawner.clearTestObjectsAndNotify();
					break;
				case SPOTANIMS:
					spawner.stopSpotanimAndNotify();
					break;
				case PROJECTILES:
					spawner.stopProjectileAndNotify();
					break;
				case SCENES:
					scenes.removeRecreatedSceneAndNotify();
					// Show everything again, undoing what recreating did
					setOnlyDrawTestObjects(false);
					break;
			}
		});

		controls.setBackground(ColorScheme.DARK_GRAY_COLOR);

		projectileDisclaimer.setText("Approximate list: HD's configured projectiles plus names that look like projectiles, " +
									 "so some may be missing or not actually projectiles");

		// Outline the spawned objects, and divide them from the full list below with a line and some space
		spawnedSection.setBackground(ColorScheme.DARK_GRAY_COLOR);
		spawnedSection.setBorder(createSectionDivider());
		spawnedRows.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Always reserve room for a few spawned objects, and scroll once there are more
		JScrollPane spawnedScroll = new JScrollPane(
			spawnedRows,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
			JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
		);
		spawnedScroll.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		spawnedScroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		spawnedScroll.getVerticalScrollBar().setUnitIncrement(SPAWNED_ROW_HEIGHT);
		spawnedScroll.setPreferredSize(new Dimension(0, SPAWNED_VISIBLE_ROWS * SPAWNED_ROW_HEIGHT + 2));

		JLabel spawnedHeader = createHeader("Spawned objects");
		spawnedSection.add(spawnedHeader, BorderLayout.NORTH);
		spawnedSection.add(spawnedScroll, BorderLayout.CENTER);

		topSection.setBackground(ColorScheme.DARK_GRAY_COLOR);
		topSection.add(controls, BorderLayout.NORTH);
		topSection.add(projectileDisclaimer, BorderLayout.CENTER);
		topSection.add(spawnedSection, BorderLayout.SOUTH);
		add(topSection, BorderLayout.NORTH);

		resultList.setBackground(ColorScheme.DARK_GRAY_COLOR);
		resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		resultList.setCellRenderer(new EntryRenderer());
		MouseAdapter mouseAdapter = new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				Entry entry = getEntryAt(e.getPoint());
				if (entry == null)
					return;
				if (SwingUtilities.isRightMouseButton(e)) {
					// Copy the gameval like the tile info overlay does, ready to paste into a JSON config
					resultList.setSelectedValue(entry, false);
					copyToClipboard(entry.name != null ? '"' + entry.name + '"' : String.valueOf(entry.id));
				} else if (SwingUtilities.isLeftMouseButton(e)) {
					switch (mode) {
						case OBJECTS:
							spawner.spawnTestObject(entry.id);
							break;
						case SPOTANIMS:
							spawner.playSpotanim(entry.id, loop);
							break;
						case PROJECTILES:
							spawner.playProjectile(entry.id, loop);
							break;
					}
				}
			}

			@Override
			public void mouseMoved(MouseEvent e) {
				int index = getIndexAt(e.getPoint());
				setMarqueeIndex(index);
				setHoveredEntry(index == -1 ? null : results.getElementAt(index));
			}

			@Override
			public void mouseExited(MouseEvent e) {
				setHoveredEntry(null);
			}
		};
		resultList.addMouseListener(mouseAdapter);
		resultList.addMouseMotionListener(mouseAdapter);
		JScrollPane scrollPane = new JScrollPane(
			resultList,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
			JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
		);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);

		listSection.setBackground(ColorScheme.DARK_GRAY_COLOR);
		// Search sits right above the list it filters
		JPanel listTop = new JPanel(new BorderLayout(0, 4));
		listTop.setBackground(ColorScheme.DARK_GRAY_COLOR);
		listTop.add(listHeader, BorderLayout.NORTH);
		listTop.add(searchBar, BorderLayout.CENTER);

		JLabel startFromLabel = createLabel("Start from");
		startFromField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		// Shown by RuneLite's look and feel while the field is empty
		startFromField.putClientProperty("JTextField.placeholderText", "1000, 1000+100, 500-679 or TFA_");
		startFromField.setToolTipText(
			"<html>1000: start from ID 1000<br>1000+100: 100 entries from ID 1000<br>500-679: only IDs 500 to 679<br>" +
			"A gameval works anywhere an ID does, like TFA_XARTH+100</html>");
		startFromField.getDocument().addDocumentListener(onDocumentChange(this::updateResults));
		JPanel startFromRow = new JPanel(new BorderLayout(6, 0));
		startFromRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		startFromRow.add(startFromLabel, BorderLayout.WEST);
		startFromRow.add(startFromField, BorderLayout.CENTER);
		JPanel listControls = new JPanel(new BorderLayout(0, 4));
		listControls.setBackground(ColorScheme.DARK_GRAY_COLOR);
		listControls.add(startFromRow, BorderLayout.NORTH);
		listControls.add(statusText, BorderLayout.CENTER);
		listTop.add(listControls, BorderLayout.SOUTH);
		listSection.add(listTop, BorderLayout.NORTH);
		listSection.add(scrollPane, BorderLayout.CENTER);
		add(listSection, BorderLayout.CENTER);

		createScenesSection();

		setSpawnedObjects(List.of());
		setFilter(Filter.ALL);
		setMode(Mode.OBJECTS);
	}

	@Override
	public void onActivate() {
		// Retries if the definitions couldn't be read from the client last time
		spawner.loadTestObjectDefinitions(this::setDefinitions);
		if (!spotanimsLoaded)
			spawner.loadSpotanimNames(this::setSpotanims);
		if (mode == Mode.SCENES)
			refreshSceneCaptures(null);
	}

	/**
	 * Build the tab for capturing the scene to a file, and recreating saved captures in the player's region
	 */
	private void createScenesSection() {
		JLabel nameLabel = createLabel("Name");
		sceneNameField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		sceneNameField.putClientProperty("JTextField.placeholderText", "Defaults to environment & time");
		JPanel nameRow = new JPanel(new BorderLayout(6, 0));
		nameRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		nameRow.add(nameLabel, BorderLayout.WEST);
		nameRow.add(sceneNameField, BorderLayout.CENTER);

		JComboBox<SceneCapture.Area> areaList = new JComboBox<>(SceneCapture.Area.values());
		areaList.setSelectedItem(SceneCapture.Area.SCENE);
		JButton captureButton = createButton("Capture scene");
		captureButton.addActionListener(e -> scenes.captureScene(
			sceneNameField.getText().trim(),
			(SceneCapture.Area) areaList.getSelectedItem()
		));

		JPanel captureColumn = new JPanel(new GridLayout(0, 1, 0, 4));
		captureColumn.setBackground(ColorScheme.DARK_GRAY_COLOR);
		captureColumn.add(createHeader("Capture scene"));
		captureColumn.add(nameRow);
		captureColumn.add(areaList);
		captureColumn.add(captureButton);

		JTextArea captureInfo = createWrappingText();
		captureInfo.setText("Saves the objects, NPCs, ground and environment the game is drawing in the chosen area to " +
							".runelite/117hd/scene_captures.");

		styleButton(onlyTestObjectsButton);
		onlyTestObjectsButton.setToolTipText(
			"Only draw spawned & recreated objects and your character, hiding the ground, objects, NPCs and other players");
		onlyTestObjectsButton.addActionListener(e -> setOnlyDrawTestObjects(onlyTestObjectsButton.isSelected()));
		recreateButton.addActionListener(e -> recreateSelectedSceneCapture());

		// Saved captures scroll once there are more than fit, and double-clicking one recreates it
		sceneCaptureList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		sceneCaptureList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		sceneCaptureList.setFont(FontManager.getRunescapeSmallFont());
		sceneCaptureList.setVisibleRowCount(SAVED_CAPTURES_VISIBLE_ROWS);
		sceneCaptureList.addListSelectionListener(e -> recreateButton.setEnabled(sceneCaptureList.getSelectedValue() != null));
		sceneCaptureList.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e))
					recreateSelectedSceneCapture();
			}
		});
		JScrollPane sceneCaptureScroll = new JScrollPane(
			sceneCaptureList,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
			JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
		);
		sceneCaptureScroll.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		// Three sections in the order they're used: capturing, picking a capture to recreate, then adjusting what's recreated
		JPanel capturePart = new JPanel(new BorderLayout(0, 4));
		capturePart.setBackground(ColorScheme.DARK_GRAY_COLOR);
		capturePart.setBorder(createSectionDivider());
		capturePart.add(captureColumn, BorderLayout.NORTH);
		capturePart.add(captureInfo, BorderLayout.CENTER);

		JPanel savedPart = new JPanel(new BorderLayout(0, 4));
		savedPart.setBackground(ColorScheme.DARK_GRAY_COLOR);
		savedPart.setBorder(createSectionDivider());
		// Pick up captures added, renamed or deleted in the folder outside the client
		JButton refreshButton = createIconButton(
			createRotateIcon(true),
			"Reload saved captures from the folder",
			() -> refreshSceneCaptures(null)
		);
		JPanel savedHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		savedHeader.setBackground(ColorScheme.DARK_GRAY_COLOR);
		savedHeader.add(createHeader("Saved captures"));
		savedHeader.add(Box.createHorizontalStrut(6));
		savedHeader.add(refreshButton);
		savedPart.add(savedHeader, BorderLayout.NORTH);
		savedPart.add(sceneCaptureScroll, BorderLayout.CENTER);
		savedPart.add(recreateButton, BorderLayout.SOUTH);

		recreatedSceneColumn.setBackground(ColorScheme.DARK_GRAY_COLOR);
		recreatedSceneColumn.add(createHeader("Recreated scene"));
		recreatedSceneColumn.add(onlyTestObjectsButton);
		JTextArea recreateInfo = createWrappingText();
		recreateInfo.setText("Recreated scenes line up with your region's grid, keep their height relative to you, use the " +
							 "captured environment, and stay until removed or the scene reloads");
		JPanel recreatedPart = new JPanel(new BorderLayout(0, 4));
		recreatedPart.setBackground(ColorScheme.DARK_GRAY_COLOR);
		recreatedPart.add(recreatedSceneColumn, BorderLayout.NORTH);
		recreatedPart.add(recreateInfo, BorderLayout.CENTER);

		JPanel lowerParts = new JPanel(new BorderLayout(0, 6));
		lowerParts.setBackground(ColorScheme.DARK_GRAY_COLOR);
		lowerParts.add(savedPart, BorderLayout.NORTH);
		lowerParts.add(recreatedPart, BorderLayout.CENTER);
		JPanel parts = new JPanel(new BorderLayout(0, 6));
		parts.setBackground(ColorScheme.DARK_GRAY_COLOR);
		parts.add(capturePart, BorderLayout.NORTH);
		parts.add(lowerParts, BorderLayout.CENTER);
		scenesSection.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scenesSection.add(parts, BorderLayout.NORTH);
		updateSceneCaptures(List.of(), null);
	}

	/**
	 * Recreate the selected capture, hiding everything else to see it on its own, which saves a step
	 */
	private void recreateSelectedSceneCapture() {
		String selected = sceneCaptureList.getSelectedValue();
		if (selected == null)
			return;
		scenes.recreateSceneCapture(selected + ".json");
		setOnlyDrawTestObjects(true);
	}

	private void setOnlyDrawTestObjects(boolean only) {
		onlyTestObjectsButton.setSelected(only);
		setHighlighted(onlyTestObjectsButton, only);
		scenes.setOnlyDrawTestObjects(only);
	}

	/**
	 * Select a capture once the list has been refreshed, such as one which was just saved. Must be called on the Swing thread.
	 */
	public void setSelectedSceneCapture(String fileName) {
		refreshSceneCaptures(fileName);
	}

	private void refreshSceneCaptures(@Nullable String selectFileName) {
		scenes.listSceneCaptures(fileNames -> updateSceneCaptures(fileNames, selectFileName));
	}

	private void updateSceneCaptures(List<String> fileNames, @Nullable String selectFileName) {
		String previous = selectFileName != null ? stripJsonExtension(selectFileName) : sceneCaptureList.getSelectedValue();
		String[] names = fileNames.stream().map(ObjectSpawnerPanel::stripJsonExtension).toArray(String[]::new);
		sceneCaptureList.setListData(names);
		if (previous != null) {
			sceneCaptureList.setSelectedValue(previous, true);
		} else if (names.length > 0) {
			sceneCaptureList.setSelectedIndex(0);
		}
		recreateButton.setEnabled(sceneCaptureList.getSelectedValue() != null);
	}

	private static String stripJsonExtension(String fileName) {
		return fileName.endsWith(".json") ? fileName.substring(0, fileName.length() - 5) : fileName;
	}

	@Override
	public void onDeactivate() {
		setHoveredEntry(null);
	}

	@Override
	public void removeNotify() {
		// The panel can be removed while a row is hovered, such as when the plugin stops
		marqueeTimer.stop();
		setSpawnedMarqueeLabel(null);
		super.removeNotify();
	}

	/**
	 * Show the spawned test objects, each with buttons to rotate or remove it. Must be called on the Swing thread.
	 */
	public void setSpawnedObjects(List<SpawnerTools.SpawnedObjectInfo> spawnedObjects) {
		setSpawnedMarqueeLabel(null);
		spawnedRows.removeAll();
		if (spawnedObjects.isEmpty()) {
			JLabel emptyLabel = new JLabel("Nothing spawned yet", JLabel.CENTER);
			emptyLabel.setFont(FontManager.getRunescapeSmallFont());
			emptyLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			emptyLabel.setPreferredSize(new Dimension(0, SPAWNED_ROW_HEIGHT));
			spawnedRows.add(emptyLabel);
		}
		for (int i = 0; i < spawnedObjects.size(); i++)
			spawnedRows.add(createSpawnedRow(spawnedObjects.get(i), i));
		updateSpawnedSection();
	}

	private void updateSpawnedSection() {
		spawnedSection.setVisible(mode == Mode.OBJECTS);
		spawnedSection.revalidate();
		spawnedSection.repaint();
	}

	private JPanel createSpawnedRow(SpawnerTools.SpawnedObjectInfo info, int index) {
		Color background = index % 2 == 0 ? ColorScheme.DARKER_GRAY_COLOR : ColorScheme.DARK_GRAY_COLOR;

		MarqueeLabel nameLabel = new MarqueeLabel();
		nameLabel.setText(info.label);
		nameLabel.setFont(FontManager.getRunescapeSmallFont());
		nameLabel.setForeground(Color.WHITE);
		JLabel detailLabel = new JLabel(info.objectId + " · " + info.rotationDegrees + "°");
		detailLabel.setFont(FontManager.getRunescapeSmallFont());
		detailLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		JPanel text = new JPanel(new GridLayout(0, 1));
		text.setBackground(background);
		text.add(nameLabel);
		text.add(detailLabel);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
		buttons.setBackground(background);
		buttons.add(createIconButton(createRotateIcon(false), "Rotate -90°", () -> spawner.rotateTestObject(info.handle, -1)));
		buttons.add(createIconButton(createRotateIcon(true), "Rotate +90°", () -> spawner.rotateTestObject(info.handle, 1)));
		buttons.add(createIconButton(createRemoveIcon(), "Remove", () -> spawner.removeTestObject(info.handle)));

		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(background);
		row.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 4));
		row.setPreferredSize(new Dimension(0, SPAWNED_ROW_HEIGHT));
		row.add(text, BorderLayout.CENTER);
		row.add(buttons, BorderLayout.EAST);

		// Scroll a long name while anywhere on the row is hovered, including its buttons
		MouseAdapter hover = new MouseAdapter() {
			@Override
			public void mouseEntered(MouseEvent e) {
				setSpawnedMarqueeLabel(nameLabel);
			}

			@Override
			public void mouseExited(MouseEvent e) {
				if (row.getMousePosition(true) == null && spawnedMarqueeLabel == nameLabel)
					setSpawnedMarqueeLabel(null);
			}
		};
		for (Component component : new Component[] { row, text, nameLabel, detailLabel, buttons })
			component.addMouseListener(hover);
		for (Component button : buttons.getComponents())
			button.addMouseListener(hover);
		return row;
	}

	private void setSpawnedMarqueeLabel(@Nullable MarqueeLabel label) {
		if (label == spawnedMarqueeLabel)
			return;
		if (spawnedMarqueeLabel != null) {
			spawnedMarqueeLabel.elapsedMs = -1;
			spawnedMarqueeLabel.startMs = -1;
			spawnedMarqueeLabel.repaint();
		}
		spawnedMarqueeLabel = label;
		if (label == null) {
			spawnedMarqueeTimer.stop();
		} else {
			label.startMs = System.currentTimeMillis();
			spawnedMarqueeTimer.start();
		}
	}

	private void setMode(Mode mode) {
		// Remove any preview from the list being switched away from
		setHoveredEntry(null);
		this.mode = mode;
		for (Mode m : Mode.values())
			setHighlighted(modeButtons[m.ordinal()], m == mode);
		clearButton.setText(mode.clearLabel);
		listHeader.setText("All " + mode.label.toLowerCase(Locale.ROOT));

		// Only show the settings which apply to the current list
		controls.removeAll();
		controls.add(modeRow);
		if (mode != Mode.SCENES)
			controls.add(filterRow);
		if (mode == Mode.SPOTANIMS || mode == Mode.PROJECTILES)
			controls.add(loopButton);
		if (mode == Mode.SPOTANIMS)
			controls.add(frameTimingsButton);
		// The clear button goes with what it clears where there's a section for it: the spawned objects or recreated scene
		if (mode == Mode.OBJECTS) {
			spawnedSection.add(clearButton, BorderLayout.SOUTH);
		} else if (mode == Mode.SCENES) {
			recreatedSceneColumn.add(clearButton);
		} else {
			controls.add(clearButton);
		}
		if (mode == Mode.PROJECTILES) {
			controls.add(distanceRow);
			controls.add(startHeightRow);
		}
		controls.revalidate();
		controls.repaint();

		projectileDisclaimer.setVisible(mode == Mode.PROJECTILES);
		updateSpawnedSection();
		// Divide the settings from the list like the spawned objects are, where there's no spawned objects box to do it
		topSection.setBorder(mode == Mode.SPOTANIMS || mode == Mode.PROJECTILES ? createSectionDivider() : null);

		// The scenes tab has no list to search, so it takes the list's place
		JPanel center = mode == Mode.SCENES ? scenesSection : listSection;
		if (center.getParent() != this) {
			remove(mode == Mode.SCENES ? listSection : scenesSection);
			add(center, BorderLayout.CENTER);
			revalidate();
			repaint();
		}
		if (mode == Mode.SCENES)
			refreshSceneCaptures(null);
		updateResults();
	}

	private void setFilter(Filter filter) {
		this.filter = filter;
		lightsFilterButton.setSelected(filter == Filter.HAS_LIGHTS);
		overrideFilterButton.setSelected(filter == Filter.HAS_OVERRIDE);
		updateResults();
	}

	private void updateLoopButton() {
		loopButton.setText(loop ? "Looping: on" : "Looping: off");
		setHighlighted(loopButton, loop);
	}

	private void updateFrameTimingsButton() {
		frameTimingsButton.setText(frameTimings ? "Frame timings: on" : "Frame timings: off");
		setHighlighted(frameTimingsButton, frameTimings);
	}

	private void setSpotanims(Map<String, Integer> spotanims) {
		spotanimsLoaded = !spotanims.isEmpty();
		List<Entry> list = new ArrayList<>(spotanims.size());
		spotanims.forEach((name, id) -> list.add(new Entry(id, name, null)));
		list.sort(BY_ID);
		spotanimEntries = list;
		updateProjectileEntries();
		if (mode != Mode.OBJECTS)
			updateResults();
	}

	/**
	 * Spotanims configured as projectiles in lights.json or model_overrides.json, plus those named like projectiles.
	 */
	private void updateProjectileEntries() {
		Set<Integer> configured = new HashSet<>();
		if (hdConfigIds != null) {
			configured.addAll(hdConfigIds.projectileLights);
			configured.addAll(hdConfigIds.projectileOverrides);
		}
		List<Entry> list = new ArrayList<>();
		for (Entry entry : spotanimEntries) {
			boolean namedLikeProjectile = entry.name != null &&
				PROJECTILE_NAME.matcher(entry.name).find() &&
				!NOT_PROJECTILE_NAME.matcher(entry.name).find();
			if (configured.contains(entry.id) || namedLikeProjectile)
				list.add(entry);
		}
		projectileEntries = list;
	}

	private void setDefinitions(@Nullable Map<Integer, TestObjectDefinition> definitions) {
		definitionsChecked = true;
		if (definitions == null) {
			loadedDefinitions = null;
			objectEntries = List.of();
			if (mode == Mode.OBJECTS)
				updateResults();
			return;
		}
		if (definitions == loadedDefinitions)
			return;

		loadedDefinitions = definitions;
		List<Entry> list = new ArrayList<>(definitions.size());
		definitions.forEach((id, def) -> list.add(new Entry(id, def.name, def.displayName)));
		list.sort(BY_ID);
		objectEntries = list;
		if (mode == Mode.OBJECTS)
			updateResults();
	}

	/**
	 * Flag entries with lights or model overrides. Must be called on the Swing thread.
	 */
	public void setHdConfigIds(SpawnerTools.HdConfigIds hdConfigIds) {
		this.hdConfigIds = hdConfigIds;
		updateProjectileEntries();
		if (filter != Filter.ALL || mode == Mode.PROJECTILES) {
			updateResults();
		} else {
			resultList.repaint();
		}
	}

	@Nullable
	private Set<Integer> getLightIds() {
		if (hdConfigIds == null)
			return null;
		switch (mode) {
			case OBJECTS:
				return hdConfigIds.objectLights;
			case SPOTANIMS:
				return hdConfigIds.spotanimLights;
			default:
				return hdConfigIds.projectileLights;
		}
	}

	@Nullable
	private Set<Integer> getOverrideIds() {
		if (hdConfigIds == null)
			return null;
		switch (mode) {
			case OBJECTS:
				return hdConfigIds.objectOverrides;
			case SPOTANIMS:
				return hdConfigIds.spotanimOverrides;
			default:
				return hdConfigIds.projectileOverrides;
		}
	}

	@Nullable
	private Entry getEntryAt(Point point) {
		int index = getIndexAt(point);
		return index == -1 ? null : results.getElementAt(index);
	}

	private int getIndexAt(Point point) {
		int index = resultList.locationToIndex(point);
		if (index < 0 || !resultList.getCellBounds(index, index).contains(point))
			return -1;
		return index;
	}

	/**
	 * Scroll the name of the hovered row if it's too long, only animating while a row is hovered.
	 */
	private void setMarqueeIndex(int index) {
		if (index == marqueeIndex)
			return;
		repaintRow(marqueeIndex);
		marqueeIndex = index;
		marqueeStartMs = System.currentTimeMillis();
		if (index == -1) {
			marqueeTimer.stop();
		} else {
			marqueeTimer.start();
		}
	}

	private void repaintRow(int index) {
		if (index < 0 || index >= results.getSize())
			return;
		var bounds = resultList.getCellBounds(index, index);
		if (bounds != null)
			resultList.repaint(bounds);
	}

	/**
	 * Preview the hovered entry at the player's location, and remove the preview once nothing is hovered.
	 */
	private void setHoveredEntry(@Nullable Entry entry) {
		if (entry == null)
			setMarqueeIndex(-1);
		int id = entry == null ? -1 : entry.id;
		if (id == hoveredId)
			return;
		hoveredId = id;
		switch (mode) {
			case OBJECTS:
				if (id == -1) {
					spawner.clearTestObjectPreview();
				} else {
					spawner.previewTestObject(id);
				}
				break;
			case SPOTANIMS:
				if (id == -1) {
					spawner.clearSpotanimPreview();
				} else {
					spawner.previewSpotanim(id);
				}
				break;
			case PROJECTILES:
				if (id == -1) {
					spawner.clearProjectilePreview();
				} else {
					spawner.previewProjectile(id);
				}
				break;
		}
	}

	private void updateResults() {
		// The list is about to change underneath the cursor
		setHoveredEntry(null);
		if (mode == Mode.SCENES)
			return;

		List<Entry> entries = mode == Mode.OBJECTS ? objectEntries : mode == Mode.SPOTANIMS ? spotanimEntries : projectileEntries;
		StartFrom start = StartFrom.parse(startFromField.getText(), entries);
		boolean invalidStart = start != null && start.unresolved != null;

		Set<Integer> filterIds = filter == Filter.HAS_LIGHTS ? getLightIds() : filter == Filter.HAS_OVERRIDE ? getOverrideIds() : null;
		boolean waitingForFilter = filter != Filter.ALL && filterIds == null;

		// Show the whole of an explicit ID range or count, but otherwise limit how many rows the list holds
		boolean isRange = start != null && !invalidStart && start.toId != -1;
		boolean hasCount = start != null && !invalidStart && start.count != -1;
		int limit = isRange ? Integer.MAX_VALUE : hasCount ? start.count : MAX_RESULTS;

		String query = cleanInput(searchBar.getText()).toLowerCase(Locale.ROOT);
		// Also match when each typed word appears somewhere in the entry, in any order, like "xarth fire"
		List<String> words = new ArrayList<>();
		for (String word : searchBar.getText().split("\\s+")) {
			word = cleanInput(word).toLowerCase(Locale.ROOT);
			if (!word.isEmpty())
				words.add(word);
		}
		List<Entry> matches = new ArrayList<>();
		int matchCount = 0;
		if (!waitingForFilter && !invalidStart) {
			for (Entry entry : entries) {
				if (start != null && !start.includes(entry))
					continue;
				if (filterIds != null && !filterIds.contains(entry.id))
					continue;
				if (!query.isEmpty() && !entry.searchText.contains(query) && !containsAll(entry.searchText, words))
					continue;
				if (matchCount++ < limit)
					matches.add(entry);
			}
		}
		results.setEntries(matches);

		boolean loaded = mode == Mode.OBJECTS ? loadedDefinitions != null : spotanimsLoaded;
		if (!loaded) {
			setStatus(mode != Mode.OBJECTS ? "Loading spotanims..." :
				definitionsChecked ? "Couldn't read object definitions from the client" : "Loading objects...");
		} else if (waitingForFilter) {
			setStatus("Loading lights & model overrides...");
		} else if (invalidStart) {
			setStatus("No ID or gameval matching " + start.unresolved);
		} else if (isRange) {
			setStatus(String.format("%d matches in IDs %d-%d.\nClick to %s, right-click to copy", matchCount, start.fromId, start.toId, mode.action));
		} else if (hasCount) {
			setStatus(String.format(
				"Showing %d of %d from %s.\nClick to %s, right-click to copy",
				Math.min(matchCount, start.count),
				matchCount,
				"ID " + start.fromId,
				mode.action
			));
		} else if (matchCount > MAX_RESULTS) {
			setStatus(String.format("Showing %d of %d.\nClick to %s, right-click to copy", MAX_RESULTS, matchCount, mode.action));
		} else {
			setStatus(String.format("%d matches.\nClick to %s, right-click to copy", matchCount, mode.action));
		}
	}

	private void setStatus(String text) {
		statusText.setText(text);
	}

	/**
	 * A read-only text area, which wraps to its actual width, unlike HTML labels which only roughly follow a set width
	 */
	private static JTextArea createWrappingText() {
		JTextArea text = new JTextArea() {
			// Before it has a width, wrapped text measures as one word per line, and RuneLite grows the whole client
			// window to fit a sidebar's minimum size, so don't let the text dictate one
			@Override
			public Dimension getMinimumSize() {
				return new Dimension(0, 0);
			}
		};
		text.setFont(FontManager.getRunescapeSmallFont());
		text.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		text.setOpaque(false);
		text.setEditable(false);
		text.setFocusable(false);
		text.setLineWrap(true);
		text.setWrapStyleWord(true);
		text.setBorder(BorderFactory.createEmptyBorder());
		return text;
	}

	private void copyToClipboard(String toCopy) {
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(toCopy), null);
		spawner.postMessage("Copied to clipboard: " + toCopy);
	}

	/**
	 * A line under a section, dividing it from the next one with the same room on either side in every tab
	 */
	private static Border createSectionDivider() {
		return BorderFactory.createCompoundBorder(
			BorderFactory.createEmptyBorder(0, 0, SECTION_GAP, 0),
			BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
				BorderFactory.createEmptyBorder(0, 0, SECTION_GAP, 0)
			)
		);
	}

	private static JLabel createLabel(String text) {
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(Color.WHITE);
		return label;
	}

	private static JLabel createHeader(String text) {
		JLabel header = new JLabel(text);
		header.setFont(FontManager.getRunescapeBoldFont());
		header.setForeground(Color.WHITE);
		return header;
	}

	private static JButton createButton(String text) {
		JButton button = new JButton(text);
		styleButton(button);
		return button;
	}

	private static void styleButton(AbstractButton button) {
		button.setFocusPainted(false);
	}

	private static void setHighlighted(AbstractButton button, boolean on) {
		button.setForeground(on ? ColorScheme.BRAND_ORANGE : Color.WHITE);
	}

	private static DocumentListener onDocumentChange(Runnable action) {
		return new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				action.run();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				action.run();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				action.run();
			}
		};
	}

	private static JButton createIconButton(BufferedImage icon, String tooltip, Runnable action) {
		JButton button = new JButton(new ImageIcon(icon));
		styleButton(button);
		button.setToolTipText(tooltip);
		button.setMargin(new Insets(0, 0, 0, 0));
		button.setPreferredSize(new Dimension(22, 22));
		button.addActionListener(e -> action.run());
		return button;
	}

	/**
	 * Draw a small cube for the sidebar button, instead of shipping an image.
	 */
	static BufferedImage createIcon() {
		return createCubeIcon(16, SIDEBAR_ICON_COLOR);
	}

	/**
	 * Draw the sidebar's cube at any size, designed at 16x16 pixels.
	 */
	private static BufferedImage createCubeIcon(int size, Color color) {
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.scale(size / 16.0, size / 16.0);
		g.setColor(color);
		g.setStroke(new BasicStroke(1.5f));
		int[] xs = { 8, 14, 14, 8, 2, 2 };
		int[] ys = { 1, 4, 11, 15, 11, 4 };
		g.drawPolygon(xs, ys, xs.length);
		g.drawLine(2, 4, 8, 7);
		g.drawLine(14, 4, 8, 7);
		g.drawLine(8, 7, 8, 15);
		g.dispose();
		return image;
	}

	/**
	 * Draw a circular arrow, going three quarters of the way around from the top.
	 */
	private static BufferedImage createRotateIcon(boolean clockwise) {
		BufferedImage image = new BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(Color.WHITE);
		g.setStroke(new BasicStroke(1.3f));
		g.drawArc(2, 2, 8, 8, 90, clockwise ? -270 : 270);
		// The arc ends on the left when going clockwise, or on the right otherwise, heading upwards
		int tipX = clockwise ? 2 : 10;
		g.fillPolygon(new int[] { tipX - 2, tipX + 2, tipX }, new int[] { 6, 6, 3 }, 3);
		g.dispose();
		return image;
	}

	/**
	 * Draw a lightbulb at any size, designed at 12x12 pixels, flagging entries with lights.
	 */
	private static BufferedImage createLightbulbIcon(int size) {
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.scale(size / 12.0, size / 12.0);
		g.setColor(new Color(0xF5D547));
		g.fillOval(2, 0, 8, 8);
		g.fillRect(4, 6, 4, 2);
		g.setColor(ColorScheme.LIGHT_GRAY_COLOR);
		g.fillRect(4, 8, 4, 3);
		g.dispose();
		return image;
	}

	private static BufferedImage createRemoveIcon() {
		BufferedImage image = new BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(Color.WHITE);
		g.setStroke(new BasicStroke(1.5f));
		g.drawLine(3, 3, 9, 9);
		g.drawLine(9, 3, 3, 9);
		g.dispose();
		return image;
	}

	/**
	 * Where the list starts, given as IDs or gamevals, since a gameval is just another way of writing its ID.
	 * 1000 or TFA_XARTH starts from there, 1000+100 or TFA_XARTH+100 shows that many entries from there,
	 * and 500-679 only shows that ID range. A partial gameval resolves to the lowest ID whose gameval starts with it.
	 * Gamevals only contain capital letters, digits & underscores, so + and - can't be part of one.
	 */
	private static class StartFrom {
		private static final Pattern DIGITS = Pattern.compile("\\d+");

		final int fromId;
		// Inclusive end of an ID range, or -1 for no end
		final int toId;
		// How many entries to show from the start, or -1 for the usual limit
		final int count;
		// Any part of the input which doesn't match an ID or gameval, or null if it all resolved
		@Nullable
		final String unresolved;

		private StartFrom(int fromId, int toId, int count, @Nullable String unresolved) {
			this.fromId = fromId;
			this.toId = toId;
			this.count = count;
			this.unresolved = unresolved;
		}

		private static StartFrom unresolved(String text) {
			return new StartFrom(-1, -1, -1, text.trim());
		}

		/**
		 * Parse the input, resolving gamevals using the entries, which must be sorted by ID.
		 */
		@Nullable
		static StartFrom parse(String text, List<Entry> entries) {
			text = cleanInput(text);
			if (text.isEmpty())
				return null;

			// Until the number after a + or - has been typed, just use the start
			if (text.endsWith("+") || text.endsWith("-"))
				text = text.substring(0, text.length() - 1).trim();
			if (text.isEmpty())
				return null;

			int plus = text.indexOf('+');
			if (plus != -1) {
				String start = text.substring(0, plus);
				String count = text.substring(plus + 1).trim();
				Integer fromId = resolve(start, entries);
				if (fromId == null)
					return unresolved(start);
				if (!DIGITS.matcher(count).matches())
					return unresolved(count);
				try {
					return new StartFrom(fromId, -1, Integer.parseInt(count), null);
				} catch (NumberFormatException ex) {
					return unresolved(count);
				}
			}

			int dash = text.indexOf('-');
			if (dash != -1) {
				String first = text.substring(0, dash);
				String last = text.substring(dash + 1);
				Integer a = resolve(first, entries);
				if (a == null)
					return unresolved(first);
				Integer b = resolve(last, entries);
				if (b == null)
					return unresolved(last);
				return new StartFrom(Math.min(a, b), Math.max(a, b), -1, null);
			}

			Integer fromId = resolve(text, entries);
			return fromId == null ? unresolved(text) : new StartFrom(fromId, -1, -1, null);
		}

		/**
		 * Turn an ID or gameval into an ID, preferring an exact gameval, then the lowest ID whose gameval starts with it.
		 */
		@Nullable
		private static Integer resolve(String token, List<Entry> entries) {
			token = token.trim();
			if (token.isEmpty())
				return null;
			if (DIGITS.matcher(token).matches()) {
				try {
					return Integer.parseInt(token);
				} catch (NumberFormatException ex) {
					return null;
				}
			}

			// Gamevals are upper case, so compare in upper case
			String name = token.toUpperCase(Locale.ROOT);
			Integer prefixMatch = null;
			for (Entry entry : entries) {
				if (entry.name == null)
					continue;
				if (entry.name.equals(name))
					return entry.id;
				if (prefixMatch == null && entry.name.startsWith(name))
					prefixMatch = entry.id;
			}
			return prefixMatch;
		}

		boolean includes(Entry entry) {
			return entry.id >= fromId && (toId == -1 || entry.id <= toId);
		}
	}

	private static boolean containsAll(String text, List<String> words) {
		if (words.size() < 2)
			return false;
		for (String word : words)
			if (!text.contains(word))
				return false;
		return true;
	}

	/**
	 * Drop quotes, commas & spaces, so gamevals copied straight out of a JSON config like "TFA_XARTH", still match.
	 * Gamevals and IDs never contain them, and in-game names are matched without spaces too.
	 */
	private static String cleanInput(String text) {
		StringBuilder sb = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c != '"' && c != ',' && !Character.isWhitespace(c))
				sb.append(c);
		}
		return sb.toString();
	}

	private static class Entry {
		final int id;
		@Nullable
		final String name;
		@Nullable
		final String displayName;
		// Each field lower case without spaces, separated so a search can't match across two fields
		final String searchText;

		Entry(int id, @Nullable String name, @Nullable String displayName) {
			this.id = id;
			this.name = name;
			this.displayName = displayName;
			searchText = cleanInput(id + "|" + (name == null ? "" : name) + "|" + (displayName == null ? "" : displayName))
				.toLowerCase(Locale.ROOT);
		}
	}

	/**
	 * Rows which stretch to the scroll pane's width, but keep their own height so they can scroll.
	 */
	private static class SpawnedRowsPanel extends JPanel implements Scrollable {
		SpawnedRowsPanel() {
			super(new GridLayout(0, 1, 0, 0));
		}

		@Override
		public Dimension getPreferredScrollableViewportSize() {
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
			return SPAWNED_ROW_HEIGHT;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
			return visibleRect.height;
		}

		@Override
		public boolean getScrollableTracksViewportWidth() {
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight() {
			return false;
		}
	}

	/**
	 * A label which, while marked as animating, scrolls text that's too long instead of cutting it off.
	 */
	private static class MarqueeLabel extends JLabel {
		// How long the marquee has been running, or -1 to cut off long text as usual
		long elapsedMs = -1;
		// When the marquee started, or -1 if the elapsed time is set directly instead
		long startMs = -1;

		@Override
		protected void paintComponent(Graphics g) {
			if (startMs != -1)
				elapsedMs = System.currentTimeMillis() - startMs;
			String text = getText();
			FontMetrics metrics = getFontMetrics(getFont());
			Insets insets = getInsets();
			int available = getWidth() - insets.left - insets.right;
			int overflow = text == null ? 0 : metrics.stringWidth(text) - available;
			if (elapsedMs < 0 || overflow <= 0) {
				super.paintComponent(g);
				return;
			}

			// Pause at the start, scroll until the end is visible, pause again, then start over
			long scrollMs = overflow * 1000L / MARQUEE_SPEED;
			long t = elapsedMs % (MARQUEE_PAUSE_MS + scrollMs + MARQUEE_PAUSE_MS);
			int offset = t < MARQUEE_PAUSE_MS ? 0 :
				t < MARQUEE_PAUSE_MS + scrollMs ? (int) ((t - MARQUEE_PAUSE_MS) * MARQUEE_SPEED / 1000) :
					overflow;

			Graphics2D g2 = (Graphics2D) g.create();
			g2.clipRect(insets.left, 0, available, getHeight());
			g2.setFont(getFont());
			g2.setColor(getForeground());
			int y = insets.top + (getHeight() - insets.top - insets.bottom - metrics.getHeight()) / 2 + metrics.getAscent();
			g2.drawString(text, insets.left - offset, y);
			g2.dispose();
		}
	}

	private static class ResultsModel extends AbstractListModel<Entry> {
		private List<Entry> entries = List.of();

		void setEntries(List<Entry> entries) {
			int previousSize = this.entries.size();
			this.entries = entries;
			if (previousSize > 0)
				fireIntervalRemoved(this, 0, previousSize - 1);
			if (!entries.isEmpty())
				fireIntervalAdded(this, 0, entries.size() - 1);
		}

		@Override
		public int getSize() {
			return entries.size();
		}

		@Override
		public Entry getElementAt(int index) {
			return entries.get(index);
		}
	}

	private class EntryRenderer extends JPanel implements ListCellRenderer<Entry> {
		private final MarqueeLabel nameLabel = new MarqueeLabel();
		private final JLabel overrideFlag = new JLabel(OVERRIDE_FLAG_ICON);
		private final JLabel lightsFlag = new JLabel(LIGHTS_FLAG_ICON);
		private final JLabel detailLabel = new JLabel();
		private final JPanel detailRow = new JPanel(new BorderLayout(4, 0));
		private final JPanel flags = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));

		EntryRenderer() {
			setLayout(new GridLayout(0, 1));
			setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
			nameLabel.setFont(FontManager.getRunescapeSmallFont());
			detailLabel.setFont(FontManager.getRunescapeSmallFont());
			detailLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

			// Flag model overrides & lights in the bottom right corner
			detailRow.setOpaque(false);
			flags.setOpaque(false);
			flags.add(overrideFlag);
			flags.add(lightsFlag);
			detailRow.add(detailLabel, BorderLayout.CENTER);
			detailRow.add(flags, BorderLayout.EAST);
			add(nameLabel);
			add(detailRow);
		}

		@Override
		public Component getListCellRendererComponent(
			JList<? extends Entry> list,
			Entry entry,
			int index,
			boolean isSelected,
			boolean cellHasFocus
		) {
			Set<Integer> lightIds = getLightIds();
			Set<Integer> overrideIds = getOverrideIds();
			boolean hasLights = lightIds != null && lightIds.contains(entry.id);
			boolean hasOverride = overrideIds != null && overrideIds.contains(entry.id);
			lightsFlag.setVisible(hasLights);
			overrideFlag.setVisible(hasOverride);

			nameLabel.setText(entry.name != null ? entry.name : entry.displayName != null ? entry.displayName : "Object " + entry.id);
			nameLabel.setForeground(isSelected ? ColorScheme.BRAND_ORANGE : Color.WHITE);
			nameLabel.elapsedMs = index == marqueeIndex ? System.currentTimeMillis() - marqueeStartMs : -1;
			detailLabel.setText(entry.id + (entry.name != null && entry.displayName != null ? " · " + entry.displayName : ""));
			setBackground(index % 2 == 0 ? ColorScheme.DARKER_GRAY_COLOR : ColorScheme.DARK_GRAY_COLOR);
			return this;
		}
	}
}
