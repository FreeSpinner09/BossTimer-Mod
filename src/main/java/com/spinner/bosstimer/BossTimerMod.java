package com.spinner.bosstimer;

import com.google.gson.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.File;
import java.io.FileReader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BossTimerMod implements ModInitializer {
	private static final Map<Integer, List<String>> commandConfigs = new ConcurrentHashMap<>();
	private static boolean configLoaded = false;

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			registerCommands(dispatcher);
		});
	}

	private void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(
				CommandManager.literal("starttimer")
						.requires(source -> Permissions.check(source, "bosstimer.start", source.hasPermissionLevel(2)))
						.then(CommandManager.argument("seconds", IntegerArgumentType.integer(1))
								.then(CommandManager.argument("configId", IntegerArgumentType.integer(1))
										.executes(context -> {
											int seconds = IntegerArgumentType.getInteger(context, "seconds");
											int configId = IntegerArgumentType.getInteger(context, "configId");
											ServerCommandSource source = context.getSource();

											if (!configLoaded) {
												loadConfig();
												configLoaded = true;
											}

											if (!commandConfigs.containsKey(configId)) {
												source.sendError(Text.literal("No config found for ID " + configId));
												return 0;
											}

											source.sendFeedback(() -> Text.literal("Timer started for " + seconds + " seconds (Config ID: " + configId + ")"), false);

											startBossTimer(seconds, configId, source.getServer());

											return 1;
										})))
		);
	}

	private void loadConfig() {
		try {
			File configFile = new File("config/bosstimer_commands.json");
			if (!configFile.exists()) {
				System.out.println("[BossTimerMod] Config file not found: " + configFile.getAbsolutePath());
				return;
			}

			JsonObject json = JsonParser.parseReader(new FileReader(configFile)).getAsJsonObject();
			for (String key : json.keySet()) {
				int id = Integer.parseInt(key);
				JsonArray array = json.getAsJsonArray(key);
				List<String> cmds = new ArrayList<>();
				for (JsonElement el : array) {
					cmds.add(el.getAsString());
				}
				commandConfigs.put(id, cmds);
			}

			System.out.println("[BossTimerMod] Loaded config with " + commandConfigs.size() + " entries.");
		} catch (Exception e) {
			System.err.println("[BossTimerMod] Failed to load config: " + e.getMessage());
			e.printStackTrace();
		}
	}

	private void startBossTimer(int seconds, int configId, MinecraftServer server) {
		ServerBossBar bossBar = new ServerBossBar(
				Text.literal("Event starting soon..."),
				BossBar.Color.RED,
				BossBar.Style.PROGRESS
		);
		bossBar.setPercent(1.0f);
		server.getPlayerManager().getPlayerList().forEach(bossBar::addPlayer);

		Timer timer = new Timer();
		final int[] remaining = {seconds};

		timer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				if (remaining[0] <= 0) {
					bossBar.setName(Text.literal("Event started!"));
					server.getPlayerManager().getPlayerList().forEach(bossBar::removePlayer);
					timer.cancel();

					// Run commands
					List<String> commands = commandConfigs.get(configId);
					if (commands != null) {
						for (String cmd : commands) {
							server.getCommandManager().executeWithPrefix(server.getCommandSource(), cmd);
						}
					}
					return;
				}

				float progress = remaining[0] / (float) seconds;
				bossBar.setName(Text.literal("Starting in " + remaining[0] + "s"));
				bossBar.setPercent(progress);

				remaining[0]--;
			}
		}, 0, 1000);
	}
}
