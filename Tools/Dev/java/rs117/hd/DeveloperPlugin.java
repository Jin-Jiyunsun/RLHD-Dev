package rs117.hd;

import com.google.inject.Provides;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import rs117.hd.model.ModelExporter;
import rs117.hd.model.ModelImporter;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;

@Slf4j
@Singleton
@PluginDescriptor(
	name = "117 HD Developer",
	description = "development and profiling tools for 117 HD",
	tags = {"117", "hd", "developer", "development", "profiler"}
)
public class DeveloperPlugin extends Plugin {
	public static final ResourcePath MODEL_EXPORTS = Props.getFile(
		"rlhd.model-exports-path", () -> path("..", "model-exports"));

	@Inject
	private ClientThread clientThread;

	@Inject
	private PluginManager pluginManager;

	private ModelExporter modelExporter;
	private ModelImporter modelImporter;

	@Provides
	DeveloperConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(DeveloperConfig.class);
	}

	@Override
	protected void startUp() {
		clientThread.invoke(() -> {
			var optional = pluginManager.getPlugins().stream()
				.filter(p -> p instanceof HdPlugin)
				.findFirst();
			if (optional.isEmpty())
				return false;

			HdPlugin plugin = (HdPlugin) optional.get();
			injector = plugin.getInjector();

			modelExporter = injector.getInstance(ModelExporter.class);
			modelImporter = injector.getInstance(ModelImporter.class);

			return true;
		});
	}

	@Override
	protected void shutDown() {
		clientThread.invoke(() -> {
			if (modelImporter != null)
				modelImporter.close();
			modelImporter = null;
			if (modelExporter != null)
				modelExporter.close();
			modelExporter = null;
		});
	}
}
