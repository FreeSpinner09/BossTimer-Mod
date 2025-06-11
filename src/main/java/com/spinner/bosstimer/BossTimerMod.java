package com.spinner.bosstimer;

import com.google.gson.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

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

											scheduleTimer(seconds, configId, source.getServer());

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

	private void scheduleTimer(int seconds, int configId, MinecraftServer server) {
		Timer timer = new Timer();
		timer.schedule(new TimerTask() {
			@Override
			public void run() {
				List<String> commands = commandConfigs.get(configId);
				if (commands == null) return;

				for (String cmd : commands) {
					server.getCommandManager().executeWithPrefix(server.getCommandSource(), cmd);
				}
			}
		}, seconds * 1000L);
	}
}
