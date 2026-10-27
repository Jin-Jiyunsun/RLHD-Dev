package rs117.hd.dev.spawner;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/** Decodes object definitions from the cache, since RuneLite's API doesn't expose their models & model changes */
@Slf4j
public class ObjectDefinitionParser {
	private static final int OBJECT_TYPE_CENTREPIECE = 10;
	// Windows-1252 characters for bytes 128-159, which Jagex strings use
	private static final char[] CP1252_HIGH = {
		'€', '?', '‚', 'ƒ', '„', '…', '†', '‡',
		'ˆ', '‰', 'Š', '‹', 'Œ', '?', 'Ž', '?',
		'?', '‘', '’', '“', '”', '•', '–', '—',
		'˜', '™', 'š', '›', 'œ', '?', 'ž', 'Ÿ'
	};

	/** Null if it has no models or something unknown, such as a field from a game update */
	@Nullable
	public static TestObjectDefinition parse(int id, byte[] data) {
		var def = new TestObjectDefinition();
		int[] models = null;
		int[] types = null;
		int offsetX = 0, offsetHeight = 0, offsetY = 0;
		int scaleX = 128, scaleHeight = 128, scaleY = 128;

		ByteBuffer in = ByteBuffer.wrap(data);
		try {
			while (in.hasRemaining()) {
				int opcode = in.get() & 0xFF;
				switch (opcode) {
					case 0:
						return finish(def, models, types, offsetX, offsetHeight, offsetY, scaleX, scaleHeight, scaleY);
					case 1:
					case 6: {
						// Models with a placement type each, as shorts or ints
						int count = in.get() & 0xFF;
						if (count > 0) {
							models = new int[count];
							types = new int[count];
							for (int i = 0; i < count; i++) {
								models[i] = opcode == 1 ? in.getShort() & 0xFFFF : in.getInt();
								types[i] = in.get() & 0xFF;
							}
						}
						break;
					}
					case 5:
					case 7: {
						// Models without placement types, as shorts or ints
						int count = in.get() & 0xFF;
						if (count > 0) {
							types = null;
							models = new int[count];
							for (int i = 0; i < count; i++)
								models[i] = opcode == 5 ? in.getShort() & 0xFFFF : in.getInt();
						}
						break;
					}
					case 2:
						def.displayName = readString(in);
						break;
					case 24: {
						int animation = in.getShort() & 0xFFFF;
						def.animation = animation == 0xFFFF ? -1 : animation;
						break;
					}
					case 29:
						def.ambient = in.get();
						break;
					case 39:
						def.contrast = in.get() * 25;
						break;
					case 40:
					case 41: {
						int count = in.get() & 0xFF;
						short[] from = new short[count];
						short[] to = new short[count];
						for (int i = 0; i < count; i++) {
							from[i] = in.getShort();
							to[i] = in.getShort();
						}
						if (opcode == 40) {
							def.recolorFrom = from;
							def.recolorTo = to;
						} else {
							def.retextureFrom = from;
							def.retextureTo = to;
						}
						break;
					}
					case 65:
						scaleX = in.getShort() & 0xFFFF;
						break;
					case 66:
						scaleHeight = in.getShort() & 0xFFFF;
						break;
					case 67:
						scaleY = in.getShort() & 0xFFFF;
						break;
					// Model offsets are signed, unlike most other shorts
					case 70:
						offsetX = in.getShort();
						break;
					case 71:
						offsetHeight = in.getShort();
						break;
					case 72:
						offsetY = in.getShort();
						break;
					case 62:
						def.mirrored = true;
						break;
					// Bending the model to follow the ground, either fully or only its lower part
					case 21:
						def.contouredGround = 0;
						break;
					case 81:
						def.contouredGround = (in.get() & 0xFF) * 256;
						break;

					// The rest doesn't affect how it looks
					case 17:
					case 18:
					case 22:
					case 23:
					case 27:
					case 64:
					case 73:
					case 74:
					case 89:
					case 90:
					case 94:
						break;
					case 14:
					case 15:
					case 19:
					case 28:
					case 69:
					case 75:
					case 91:
					case 95:
					case 96:
						in.get();
						break;
					case 30:
					case 31:
					case 32:
					case 33:
					case 34:
						readString(in);
						break;
					case 42:
					case 61:
					case 68:
					case 82:
						in.getShort();
						break;
					case 77:
					case 92: {
						// Varbit, varp, an optional default for 92, then the IDs to swap to
						in.getShort();
						in.getShort();
						if (opcode == 92)
							in.getShort();
						int count = in.get() & 0xFF;
						for (int i = 0; i <= count; i++)
							in.getShort();
						break;
					}
					case 78:
						in.getShort();
						in.get();
						in.get();
						break;
					case 79: {
						in.getShort();
						in.getShort();
						in.get();
						in.get();
						int count = in.get() & 0xFF;
						for (int i = 0; i < count; i++)
							in.getShort();
						break;
					}
					case 93:
						in.get();
						in.getShort();
						in.get();
						in.getShort();
						break;
					case 100:
						in.get();
						in.get();
						readString(in);
						break;
					case 101:
						in.get();
						in.getShort();
						in.getShort();
						in.getInt();
						in.getInt();
						readString(in);
						break;
					case 102:
						in.get();
						in.getShort();
						in.getShort();
						in.getShort();
						in.getInt();
						in.getInt();
						readString(in);
						break;
					case 249: {
						int count = in.get() & 0xFF;
						for (int i = 0; i < count; i++) {
							int type = in.get() & 0xFF;
							// The parameter's 3 byte key
							in.get();
							in.getShort();
							if (type == 1) {
								readString(in);
							} else if (type == 2) {
								in.getLong();
							} else {
								in.getInt();
							}
						}
						break;
					}
					default:
						log.debug("Unknown opcode {} in object definition {}", opcode, id);
						return null;
				}
			}
		} catch (BufferUnderflowException ex) {
			log.debug("Object definition {} ended unexpectedly", id);
			return null;
		}
		return finish(def, models, types, offsetX, offsetHeight, offsetY, scaleX, scaleHeight, scaleY);
	}

	@Nullable
	private static TestObjectDefinition finish(
		TestObjectDefinition def,
		@Nullable int[] models,
		@Nullable int[] types,
		int offsetX, int offsetHeight, int offsetY,
		int scaleX, int scaleHeight, int scaleY
	) {
		if (models == null || models.length == 0)
			return null;

		// Prefer the regular centrepiece model
		def.models = models;
		def.allModels = models;
		def.modelTypes = types;
		if (types != null) {
			def.models = new int[] { models[0] };
			for (int i = 0; i < types.length; i++) {
				if (types[i] == OBJECT_TYPE_CENTREPIECE) {
					def.models = new int[] { models[i] };
					break;
				}
			}
		}
		if (scaleX != 128 || scaleHeight != 128 || scaleY != 128)
			def.scale = new int[] { scaleX, scaleHeight, scaleY };
		if (offsetX != 0 || offsetHeight != 0 || offsetY != 0)
			def.offset = new int[] { offsetX, offsetHeight, offsetY };
		if ("null".equals(def.displayName))
			def.displayName = null;
		return def;
	}

	/** A zero-terminated Windows-1252 string */
	private static String readString(ByteBuffer in) {
		StringBuilder sb = new StringBuilder();
		int b;
		while ((b = in.get() & 0xFF) != 0)
			sb.append(b >= 128 && b < 160 ? CP1252_HIGH[b - 128] : (char) b);
		return sb.toString();
	}
}
