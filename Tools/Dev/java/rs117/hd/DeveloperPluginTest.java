package rs117.hd;

import java.io.InputStream;
import java.util.Properties;

import com.google.inject.*;
import com.google.inject.Module;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.config.Config;
import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.plugins.PluginDescriptor;
import rs117.hd.utils.Props;

/**
 * The developer repo's own copy of 117 HD's HdPluginTest, so the RLHD submodule's launcher can stay as it is upstream.
 */
@Slf4j
public class DeveloperPluginTest
{
	public static void main(String[] args) throws Exception
	{
		log.warn(
			"If this is your first time using the DeveloperPluginTest run configuration, " +
			"this window will filter on WARN-level logging by default"
		);

		Props.DEVELOPMENT = true;
		Props.set("rlhd.resource-path", "src/main/resources");
		ClassLoader.getSystemClassLoader().setDefaultAssertionStatus(true);
		useLatestPluginHub();
		ExternalPluginManager.loadBuiltin(DeveloperPlugin.class, HdPlugin.class);
		RuneLite.main(args);
	}

	private static void useLatestPluginHub()
	{
		if (System.getProperty("runelite.pluginhub.version") == null)
		{
			try
			{
				Properties props = new Properties();
				try (InputStream in = RuneLiteProperties.class.getResourceAsStream("runelite.properties"))
				{
					props.load(in);
				}

				String version = props.getProperty("runelite.pluginhub.version");
				String[] parts = version.split("[.-]");
				if (parts.length > 3 && parts[3].equals("SNAPSHOT"))
				{
					int patch = Integer.parseInt(parts[2]) - 1;
					version = parts[0] + "." + parts[1] + "." + patch;
					log.info("Detected SNAPSHOT version with no manually specified plugin-hub version. " +
							"Setting runelite.pluginhub.version to {}", version);
					System.setProperty("runelite.pluginhub.version", version);
				}
			}
			catch (Exception ex)
			{
				log.error("Failed to automatically use latest plugin-hub version", ex);
			}
		}
	}
}
