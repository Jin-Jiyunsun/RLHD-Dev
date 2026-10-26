package rs117.hd;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("117hd-developer")
public interface DeveloperConfig extends Config {
	String CONFIG_GROUP = "117hd-developer";

	String KEY_SPAWNER = "spawner";
	@ConfigItem(
		keyName = KEY_SPAWNER,
		name = "Spawner",
		description = "Add the 117 HD Spawner sidebar, for spawning objects, spotanims and projectiles to tune their textures & lights",
		position = 0
	)
	default boolean spawner() {
		return false;
	}
}
