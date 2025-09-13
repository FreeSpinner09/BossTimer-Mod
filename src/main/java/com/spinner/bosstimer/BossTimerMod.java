package com.spinner.bosstimer;

import com.google.gson.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.io.File;
import java.io.FileReader;
import java.util.*;
import java.util.concurrent.*;

public class BossTimerMod implements ModInitializer {
	private static final Map<String, TimerConfig> commandConfigs = new ConcurrentHashMap<>();
	private static final Map<String, TimerData> runningTimers = new ConcurrentHashMap<>();
	private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
	private static boolean configLoaded = false;

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			registerCommands(dispatcher);
		});
	}

	private void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(
				CommandManager.literal("bossbartimer")
						.requires(source -> Permissions.check(source, "bossbartimer.run", source.hasPermissionLevel(2)))
						.executes(this::listAvailableTimers)
						.then(CommandManager.literal("start")
								.requires(source -> Permissions.check(source, "bossbartimer.run", source.hasPermissionLevel(2)))
								.then(CommandManager.argument("name", StringArgumentType.word())
										.suggests((context, builder) -> {
											commandConfigs.keySet().forEach(builder::suggest);
											return builder.buildFuture();
										})
										.executes(context -> startTimerCommand(context))
								)
						)
						.then(CommandManager.literal("cancel")
								.requires(source -> Permissions.check(source, "bossbartimer.run", source.hasPermissionLevel(2)))
								.executes(context -> cancelAllTimers(context))
						)
						.then(CommandManager.literal("reload")
								.requires(source -> Permissions.check(source, "bossbartimer.reload", source.hasPermissionLevel(2)))
								.executes(context -> {
									cancelAllTimersSilent();
									loadConfig();
									configLoaded = true;
									context.getSource().sendFeedback(() -> Text.literal("Timer configurations reloaded."), true);
									return 1;
								})
						)
		);
	}

	private int listAvailableTimers(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerCommandSource source = context.getSource();
		List<String> availableTimers = new ArrayList<>(commandConfigs.keySet());

		if (availableTimers.isEmpty()) {
			source.sendError(Text.literal("No timers available."));
			return 0;
		}

		source.sendFeedback(() -> Text.literal("Available timers: " + String.join(", ", availableTimers)), false);
		return 1;
	}

	private int startTimerCommand(CommandContext<ServerCommandSource> context) {
		String name = StringArgumentType.getString(context, "name");
		ServerCommandSource source = context.getSource();

		if (!configLoaded) {
			loadConfig();
			configLoaded = true;
		}

		if (!commandConfigs.containsKey(name)) {
			source.sendError(Text.literal("No timer config found for '" + name + "'."));
			return 0;
		}

		if (runningTimers.containsKey(name)) {
			source.sendError(Text.literal("Timer '" + name + "' is already running."));
			return 0;
		}

		source.sendFeedback(() -> Text.literal("Timer '" + name + "' started."), true);
		startBossTimer(name, source.getServer());
		return 1;
	}

	private int cancelAllTimers(CommandContext<ServerCommandSource> context) {
		ServerCommandSource source = context.getSource();

		if (runningTimers.isEmpty()) {
			source.sendError(Text.literal("No active timers to cancel."));
			return 0;
		}

		List<String> canceledTimers = new ArrayList<>();
		for (Map.Entry<String, TimerData> entry : runningTimers.entrySet()) {
			TimerData timerData = entry.getValue();
			timerData.future.cancel(false);
			if (timerData.bossBar != null) timerData.bossBar.clearPlayers();
			canceledTimers.add(entry.getKey());
		}
		runningTimers.clear();

		source.sendFeedback(() -> Text.literal("Canceled all timers: " + String.join(", ", canceledTimers)), true);
		return 1;
	}

	// Silent cancel for reload
	private void cancelAllTimersSilent() {
		for (TimerData timerData : runningTimers.values()) {
			timerData.future.cancel(false);
			if (timerData.bossBar != null) timerData.bossBar.clearPlayers();
		}
		runningTimers.clear();
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
				if (name.trim().isEmpty()) continue;

				JsonObject entry = json.getAsJsonObject(name);
				int duration = entry.has("duration") ? entry.get("duration").getAsInt() : 60;
				String bossbarMessage = entry.has("bossbar_message") ? entry.get("bossbar_message").getAsString() : "Starting in %s seconds";

				List<String> before = new ArrayList<>();
				List<String> after = new ArrayList<>();
				Map<Integer, Trigger> triggers = new TreeMap<>();

				if (entry.has("before")) for (JsonElement el : entry.getAsJsonArray("before")) before.add(el.getAsString());
				if (entry.has("after")) for (JsonElement el : entry.getAsJsonArray("after")) after.add(el.getAsString());
				if (entry.has("triggers")) {
					JsonObject trigObj = entry.getAsJsonObject("triggers");
					for (String key : trigObj.keySet()) {
						try {
							int triggerTime = Integer.parseInt(key);
							if (triggerTime < 0 || triggerTime > duration) continue;
							JsonObject trig = trigObj.getAsJsonObject(key);
							String message = trig.has("message") ? trig.get("message").getAsString() : null;
							triggers.put(triggerTime, new Trigger(message));
						} catch (NumberFormatException ignored) {}
					}
				}

				commandConfigs.put(name, new TimerConfig(duration, bossbarMessage, before, after, triggers));
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
				Text.literal(String.format(config.bossbarMessage(), config.duration())),
				BossBar.Color.RED,
				BossBar.Style.PROGRESS
		);
		bossBar.setPercent(1.0f);
		server.getPlayerManager().getPlayerList().forEach(bossBar::addPlayer);

		final int[] remaining = {config.duration()};
		final ScheduledFuture<?>[] futureHolder = new ScheduledFuture<?>[1]; // Holder to allow self-cancel

		ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> server.submit(() -> {
			if (remaining[0] < 0) {
				// End timer
				bossBar.clearPlayers();
				runningTimers.remove(name);
				if (futureHolder[0] != null) futureHolder[0].cancel(false);

				for (String cmd : config.afterCommands()) {
					server.getCommandManager().executeWithPrefix(server.getCommandSource(), cmd);
				}
				return;
			}

			bossBar.setName(Text.literal(String.format(config.bossbarMessage(), remaining[0])));
			bossBar.setPercent(remaining[0] / (float) config.duration());

			if (config.triggers().containsKey(remaining[0])) {
				Trigger trig = config.triggers().get(remaining[0]);
				if (trig.message() != null) {
					server.getPlayerManager().broadcast(Text.literal(trig.message()), false);
				}
			}

			remaining[0]--;
		}), 0, 1, TimeUnit.SECONDS);

		futureHolder[0] = future;
		runningTimers.put(name, new TimerData(future, bossBar));
	}

	private record TimerData(ScheduledFuture<?> future, ServerBossBar bossBar) {}
	private record Trigger(String message) {}
	private record TimerConfig(int duration, String bossbarMessage, List<String> beforeCommands, List<String> afterCommands, Map<Integer, Trigger> triggers) {}
}
