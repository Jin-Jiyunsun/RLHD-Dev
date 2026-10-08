package rs117.hd;

import com.google.inject.Provides;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@Singleton
@PluginDescriptor(
	name = "117 HD Developer",
	description = "development and profiling tools for 117 HD",
	tags = {"117", "hd", "developer", "development", "profiler"}
)
@PluginDependency(HdPlugin.class)
public class DeveloperPlugin extends Plugin {

	@Inject
	private ClientThread clientThread;

	@Provides
	DeveloperConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(DeveloperConfig.class);
	}

	@Override
	protected void startUp() {
		clientThread.invoke(() -> {
			// Stub
		});
	}

	@Override
	protected void shutDown() {
		clientThread.invoke(() -> {
			// Stub
		});
	}
}
