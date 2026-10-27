package rs117.hd.dev.spawner;

import javax.annotation.Nullable;

/** An object's models & animation, decoded by {@link ObjectDefinitionParser} */
public class TestObjectDefinition {
	@Nullable
	public String name;
	@Nullable
	public String displayName;
	public int[] models;
	// Null types if it only has centrepiece models
	public int[] allModels;
	@Nullable
	public int[] modelTypes;
	public boolean mirrored;
	// -1 if the model doesn't follow the ground, 0 if it all does, or how much of its lower part does, out of 65536
	public int contouredGround = -1;
	public int animation = -1;
	public int ambient;
	public int contrast;
	public short[] recolorFrom;
	public short[] recolorTo;
	public short[] retextureFrom;
	public short[] retextureTo;
	public int[] scale;
	public int[] offset;
}
