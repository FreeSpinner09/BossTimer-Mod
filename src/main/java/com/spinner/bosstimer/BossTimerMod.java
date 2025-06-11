package com.spinner.bosstimer;

import com.google.gson.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.File;
import java.io.FileReader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BossTimerMod implements ModInitializer {
	private static final Map<String, TimerConfig> commandConfigs = new ConcurrentHashMap<>();

	// Store both Timer and BossBar for each running timer
	private static final Map<String, TimerData> runningTimers = new ConcurrentHashMap<>();
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
						.then(CommandManager.argument("name", StringArgumentType.word())
								.requires(source -> Permissions.check(source, "bosstimer.start", source.hasPermissionLevel(2)))
								.executes(context -> {
									String name = StringArgumentType.getString(context, "name");
									ServerCommandSource source = context.getSource();

									if (!configLoaded) {
										loadConfig();
										configLoaded = true;
									}

									if (!commandConfigs.containsKey(name)) {
										source.sendError(Text.literal("No config found for name '" + name + "'."));
										return 0;
									}

									// Check specific timer permission here:
									if (!Permissions.check(source, "bosstimer.start." + name, source.hasPermissionLevel(2))
											&& !Permissions.check(source, "bosstimer.start", source.hasPermissionLevel(2))) {
										source.sendError(Text.literal("You don't have permission to start this timer."));
										return 0;
									}

									source.sendFeedback(() -> Text.literal("Timer '" + name + "' started."), false);
									startBossTimer(name, source.getServer());
									return 1;
								})
						)
		);

		dispatcher.register(
				CommandManager.literal("canceltimer")
						.then(CommandManager.argument("name", StringArgumentType.word())
								.requires(source -> Permissions.check(source, "bosstimer.cancel", source.hasPermissionLevel(2)))
								.executes(context -> {
									String name = StringArgumentType.getString(context, "name");
									TimerData timerData = runningTimers.remove(name);
									if (timerData != null) {
										timerData.timer.cancel();

										// Remove boss bar from all players
										ServerBossBar bossBar = timerData.bossBar;
										if (bossBar != null) {
											context.getSource().getServer().getPlayerManager().getPlayerList().forEach(bossBar::removePlayer);
										}

										context.getSource().sendFeedback(() -> Text.literal("Timer '" + name + "' canceled."), false);
									} else {
										context.getSource().sendError(Text.literal("No active timer with name '" + name + "'."));
									}
									return 1;
								})
						)
		);

		dispatcher.register(
				CommandManager.literal("reloadtimers")
						.requires(source -> Permissions.check(source, "bosstimer.reload", source.hasPermissionLevel(2)))
						.executes(context -> {
							loadConfig();
							configLoaded = true;
							context.getSource().sendFeedback(() -> Text.literal("Timer configs reloaded."), false);
							return 1;
						})
		);
	}

	private void loadConfig() {
		commandConfigs.clear();
		try {
			File configFile = new File("config/bosstimer_commands.json");
			if (!configFile.exists()) {
				System.out.println("[BossTimerMod] Config file not found: " + configFile.getAbsolutePath());
				return;
			}

			JsonObject json = JsonParser.parseReader(new FileReader(configFile)).getAsJsonObject();
			for (String name : json.keySet()) {
				JsonObject entry = json.getAsJsonObject(name);
				int seconds = entry.has("seconds") ? entry.get("seconds").getAsInt() : 60;

				List<String> before = new ArrayList<>();
				List<String> after = new ArrayList<>();
				Map<Integer, Trigger> triggers = new TreeMap<>();

				if (entry.has("before")) {
					for (JsonElement el : entry.getAsJsonArray("before")) {
						before.add(el.getAsString());
					}
				}
				if (entry.has("after")) {
					for (JsonElement el : entry.getAsJsonArray("after")) {
						after.add(el.getAsString());
					}
				}
				if (entry.has("triggers")) {
					JsonObject trigObj = entry.getAsJsonObject("triggers");
					for (String key : trigObj.keySet()) {
						int triggerTime = Integer.parseInt(key);
						JsonObject trig = trigObj.getAsJsonObject(key);
						String message = trig.has("message") ? trig.get("message").getAsString() : null;
						String sound = trig.has("sound") ? trig.get("sound").getAsString() : null;
						triggers.put(triggerTime, new Trigger(message, sound));
					}
				}

				commandConfigs.put(name, new TimerConfig(seconds, before, after, triggers));
			}

			System.out.println("[BossTimerMod] Loaded config with " + commandConfigs.size() + " entries.");
		} catch (Exception e) {
			System.err.println("[BossTimerMod] Failed to load config: " + e.getMessage());
			e.printStackTrace();
		}
	}

	private void startBossTimer(String name, MinecraftServer server) {
		TimerConfig config = commandConfigs.get(name);

		for (String cmd : config.beforeCommands()) {
			server.getCommandManager().executeWithPrefix(server.getCommandSource(), cmd);
		}

		ServerBossBar bossBar = new ServerBossBar(
				Text.literal("Event starting soon..."),
				BossBar.Color.RED,
				BossBar.Style.PROGRESS
		);
		bossBar.setPercent(1.0f);
		server.getPlayerManager().getPlayerList().forEach(bossBar::addPlayer);

		Timer timer = new Timer();

		runningTimers.put(name, new TimerData(timer, bossBar));

		final int[] remaining = {config.seconds()};
		timer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				server.submit(() -> {
					if (remaining[0] <= 0) {
						bossBar.setName(Text.literal("Event started!"));
						server.getPlayerManager().getPlayerList().forEach(bossBar::removePlayer);
						runningTimers.remove(name);
						timer.cancel();

						for (String cmd : config.afterCommands()) {
							server.getCommandManager().executeWithPrefix(server.getCommandSource(), cmd);
						}
						return;
					}

					float progress = remaining[0] / (float) config.seconds();
					bossBar.setName(Text.literal("Starting in " + remaining[0] + "s"));
					bossBar.setPercent(progress);

					if (config.triggers().containsKey(remaining[0])) {
						Trigger trig = config.triggers().get(remaining[0]);

						if (trig.message() != null) {
							server.getPlayerManager().broadcast(Text.literal(trig.message()), false);
						}
						if (trig.sound() != null) {
							Identifier id = Identifier.tryParse(trig.sound());
							if (id != null) {
								SoundEvent sound = Registries.SOUND_EVENT.get(id);
								if (sound != null) {
									server.getPlayerManager().getPlayerList().forEach(player ->
											player.playSound(sound, 1.0f, 1.0f));
								}
							}
						}
					}
					remaining[0]--;
				});
			}
		}, 0, 1000);
	}

	private record TimerData(Timer timer, ServerBossBar bossBar) {}

	private record Trigger(String message, String sound) {}
	private record TimerConfig(int seconds, List<String> beforeCommands, List<String> afterCommands, Map<Integer, Trigger> triggers) {}
}
