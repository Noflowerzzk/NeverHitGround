package com.neverhittheground;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.text.Style;
import java.io.*;
import java.util.*;

public class NeverHitTheGround implements ModInitializer {
	public static final String MOD_ID = "never-hit-the-ground";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final Map<RegistryKey<World>, Map<ChunkPos, Set<BlockPos>>> placedBlocks = new HashMap<>();
	private static final Map<UUID, Long> recentRespawns = new HashMap<>();
	private static final Set<UUID> toNotify = new HashSet<>();

	public static final Identifier STEPPED_ON_NATURAL_BLOCK_ID = new Identifier("never-hit-the-ground", "stepped_on_natural_block");

	public static RegistryKey<DamageType> STEPPED_ON_NATURAL_BLOCK = RegistryKey.of(RegistryKeys.DAMAGE_TYPE, STEPPED_ON_NATURAL_BLOCK_ID);


	@Override
	public void onInitialize() {

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			UUID uuid = handler.getPlayer().getUuid();
			recentRespawns.put(uuid, System.currentTimeMillis());
			toNotify.add(uuid);
			handler.getPlayer().sendMessage(Text.of("5 秒后你将患上甲沟炎！").copy().formatted(Formatting.YELLOW, Formatting.BOLD), false);
		});

		// 也监听真正重生（不是首次登录）
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			recentRespawns.put(newPlayer.getUuid(), System.currentTimeMillis());
			toNotify.add(newPlayer.getUuid());
			newPlayer.sendMessage(Text.of("5 秒后你将患上甲沟炎！").copy().formatted(Formatting.YELLOW, Formatting.BOLD), false);
		});

		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (!world.isClient) {
				BlockPos pos = hitResult.getBlockPos().offset(hitResult.getSide());
				ChunkPos chunkPos = new ChunkPos(pos);

				placedBlocks
						.computeIfAbsent(world.getRegistryKey(), k -> new HashMap<>())
						.computeIfAbsent(chunkPos, k -> new HashSet<>())
						.add(pos.toImmutable());

				LOGGER.info("Player {} placed block at {} in chunk {}", player.getName().getString(), pos, chunkPos);
			}
			return ActionResult.PASS;
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			long now = System.currentTimeMillis();

			for (ServerWorld world : server.getWorlds()) {
				for (var player : world.getPlayers()) {
					Long respawnTime = recentRespawns.get(player.getUuid());
					if (toNotify.contains(player.getUuid()) && respawnTime != null && now - respawnTime >= 5000) {
						player.sendMessage(Text.of("你现在患有甲沟炎了！").copy().formatted(Formatting.RED, Formatting.BOLD), false);
						toNotify.remove(player.getUuid());
					}
					if (respawnTime != null && now - respawnTime < 5000) {
						continue; // 跳过刚复活的玩家
					}

					BlockPos below = player.getBlockPos().down();
					ChunkPos chunkPos = new ChunkPos(below);

					Set<BlockPos> placed = placedBlocks
							.getOrDefault(world.getRegistryKey(), Collections.emptyMap())
							.getOrDefault(chunkPos, Collections.emptySet());

					BlockState state = world.getBlockState(below);
					if (!state.isOf(Blocks.OBSIDIAN)
							&& !state.isAir()
							&& !state.isOf(Blocks.WATER)
							&& !state.isOf(Blocks.LAVA)
							&& !state.isOf(Blocks.BLACK_BED)
							&& !state.isOf(Blocks.BLUE_BED)
							&& !state.isOf(Blocks.BROWN_BED)
							&& !state.isOf(Blocks.CYAN_BED)
							&& !state.isOf(Blocks.GRAY_BED)
							&& !state.isOf(Blocks.LIGHT_BLUE_BED)
							&& !state.isOf(Blocks.LIGHT_GRAY_BED)
							&& !state.isOf(Blocks.MAGENTA_BED)
							&& !state.isOf(Blocks.ORANGE_BED)
							&& !state.isOf(Blocks.PINK_BED)
							&& !state.isOf(Blocks.PURPLE_BED)
							&& !state.isOf(Blocks.RED_BED)
							&& !state.isOf(Blocks.WHITE_BED)
							&& !state.isOf(Blocks.YELLOW_BED)
							&& !state.isOf(Blocks.GREEN_BED)
							&& !state.isOf(Blocks.LIME_BED)
							&& !state.isOf(Blocks.WHITE_BED)
							&& !state.isOf(Blocks.END_PORTAL)
							&& !state.isOf(Blocks.NETHER_PORTAL)
							&& !placed.contains(below.toImmutable())) {
//						player.kill();
//						LOGGER.info("Killed {} for stepping on non-placed block at {}
//						server.getPlayerManager().broadcast(
//								Text.literal("666！" + state.getBlock().getName().getString() + " 磕到了 " + player.getName().getString() + " 的甲沟炎！").formatted(Formatting.RED, Formatting.BOLD),
//								false
//						);
						player.damage(world.getDamageSources().create(NeverHitTheGround.STEPPED_ON_NATURAL_BLOCK), 99999f);
					}
				}
			}
		});

		ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
			try {
				Set<BlockPos> set = readChunkData(world, chunk.getPos());
				if (!set.isEmpty()) {
					placedBlocks
							.computeIfAbsent(world.getRegistryKey(), k -> new HashMap<>())
							.put(chunk.getPos(), set);
					LOGGER.info("Loaded placed block data from chunk {}", chunk.getPos());
				}
			} catch (IOException e) {
				LOGGER.error("Failed to load chunk data for {}: {}", chunk.getPos(), e.getMessage());
			}
		});

		ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
			Map<ChunkPos, Set<BlockPos>> dimMap = placedBlocks.get(world.getRegistryKey());
			if (dimMap == null) return;

			Set<BlockPos> set = dimMap.remove(chunk.getPos());
			if (set != null && !set.isEmpty()) {
				try {
					writeChunkData(world, chunk.getPos(), set);
					LOGGER.info("Saved placed block data for chunk {}", chunk.getPos());
				} catch (IOException e) {
					LOGGER.error("Failed to save chunk data for {}: {}", chunk.getPos(), e.getMessage());
				}
			}
		});
	}

	private static File getChunkDataFile(ServerWorld world, ChunkPos pos) {
		File dir = new File(world.getServer().getSavePath(WorldSavePath.ROOT).toFile(), "placed_blocks/" + world.getRegistryKey().getValue().getPath());
		if (!dir.exists()) dir.mkdirs();
		return new File(dir, "chunk_" + pos.x + "_" + pos.z + ".dat");
	}

	private static void writeChunkData(ServerWorld world, ChunkPos pos, Set<BlockPos> set) throws IOException {
		File file = getChunkDataFile(world, pos);
		try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(file))) {
			List<BlockPosSerializable> list = new ArrayList<>();
			for (BlockPos p : set) {
				list.add(BlockPosSerializable.fromBlockPos(p));
			}
			out.writeObject(list);
		}
	}

	@SuppressWarnings("unchecked")
	private static Set<BlockPos> readChunkData(ServerWorld world, ChunkPos pos) throws IOException {
		File file = getChunkDataFile(world, pos);
		if (!file.exists()) return Collections.emptySet();
		try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(file))) {
			List<BlockPosSerializable> list = (List<BlockPosSerializable>) in.readObject();
			Set<BlockPos> set = new HashSet<>();
			for (BlockPosSerializable s : list) {
				set.add(s.toBlockPos());
			}
			return set;
		} catch (ClassNotFoundException e) {
			throw new IOException("Class not found during deserialization", e);
		}
	}
}

class BlockPosSerializable implements Serializable {
	public int x, y, z;

	public BlockPosSerializable(int x, int y, int z) {
		this.x = x;
		this.y = y;
		this.z = z;
	}

	public static BlockPosSerializable fromBlockPos(BlockPos pos) {
		return new BlockPosSerializable(pos.getX(), pos.getY(), pos.getZ());
	}

	public BlockPos toBlockPos() {
		return new BlockPos(x, y, z);
	}
}