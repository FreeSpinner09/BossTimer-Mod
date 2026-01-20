package com.spinner.bosstimer;

import com.google.gson.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
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

        // 🔑 Add joining players to all active boss bars
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var player = handler.getPlayer();
            for (TimerData timer : runningTimers.values()) {
                timer.bossBar.addPlayer(player);
            }
        });
    }

    /* ================= COMMANDS ================= */

    private void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("bossbartimer")
                        .requires(source -> Permissions.check(source, "bossbartimer.run", source.hasPermissionLevel(2)))
                        .executes(this::listAvailableTimers)
                        .then(CommandManager.literal("start")
                                .then(CommandManager.argument("name", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            commandConfigs.keySet().forEach(builder::suggest);
                                            return builder.buildFuture();
                                        })
                                        .executes(this::startTimerCommand)))
                        .then(CommandManager.literal("cancel")
                                .executes(this::cancelAllTimers))
                        .then(CommandManager.literal("reload")
                                .requires(source -> Permissions.check(source, "bossbartimer.reload", source.hasPermissionLevel(2)))
                                .executes(context -> {
                                    cancelAllTimersSilent();
                                    loadConfig();
                                    configLoaded = true;
                                    context.getSource().sendFeedback(
                                            () -> Text.literal("Timer configurations reloaded."),
                                            true
                                    );
                                    return 1;
                                }))
        );
    }

    private int listAvailableTimers(CommandContext<ServerCommandSource> context) {
        if (commandConfigs.isEmpty()) {
            context.getSource().sendError(Text.literal("No timers available."));
            return 0;
        }
        context.getSource().sendFeedback(
                () -> Text.literal("Available timers: " + String.join(", ", commandConfigs.keySet())),
                false
        );
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

    /* ================= TIMER LOGIC ================= */

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

        TimerData timerData = new TimerData(bossBar, config.duration());

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() ->
                server.submit(() -> {

                    if (timerData.remaining < 0) {
                        bossBar.clearPlayers();
                        runningTimers.remove(name);

                        for (String cmd : config.afterCommands()) {
                            server.getCommandManager().executeWithPrefix(server.getCommandSource(), cmd);
                        }
                        timerData.future.cancel(false);
                        return;
                    }

                    bossBar.setName(Text.literal(
                            String.format(config.bossbarMessage(), timerData.remaining)
                    ));
                    bossBar.setPercent(timerData.remaining / (float) timerData.total);

                    Trigger trigger = config.triggers().get(timerData.remaining);
                    if (trigger != null && trigger.message() != null) {
                        server.getPlayerManager().broadcast(
                                Text.literal(trigger.message()), false
                        );
                    }

                    timerData.remaining--;

                }), 0, 1, TimeUnit.SECONDS);

        timerData.future = future;
        runningTimers.put(name, timerData);
    }

    private int cancelAllTimers(CommandContext<ServerCommandSource> context) {
        for (TimerData timer : runningTimers.values()) {
            timer.future.cancel(false);
            timer.bossBar.clearPlayers();
        }
        runningTimers.clear();
        context.getSource().sendFeedback(() -> Text.literal("Canceled all timers."), true);
        return 1;
    }

    private void cancelAllTimersSilent() {
        for (TimerData timer : runningTimers.values()) {
            timer.future.cancel(false);
            timer.bossBar.clearPlayers();
        }
        runningTimers.clear();
    }

    /* ================= CONFIG ================= */

    private void loadConfig() {
        commandConfigs.clear();
        try {
            File file = new File("config/bosstimer_commands.json");
            JsonObject json = JsonParser.parseReader(new FileReader(file)).getAsJsonObject();

            for (String name : json.keySet()) {
                JsonObject entry = json.getAsJsonObject(name);

                int duration = entry.get("duration").getAsInt();
                String msg = entry.get("bossbar_message").getAsString();

                List<String> before = new ArrayList<>();
                List<String> after = new ArrayList<>();
                Map<Integer, Trigger> triggers = new TreeMap<>();

                if (entry.has("before"))
                    entry.getAsJsonArray("before").forEach(e -> before.add(e.getAsString()));
                if (entry.has("after"))
                    entry.getAsJsonArray("after").forEach(e -> after.add(e.getAsString()));
                if (entry.has("triggers")) {
                    for (String key : entry.getAsJsonObject("triggers").keySet()) {
                        triggers.put(
                                Integer.parseInt(key),
                                new Trigger(entry.getAsJsonObject("triggers")
                                        .getAsJsonObject(key)
                                        .get("message").getAsString())
                        );
                    }
                }

                commandConfigs.put(name,
                        new TimerConfig(duration, msg, before, after, triggers));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /* ================= DATA ================= */

    private static class TimerData {
        ScheduledFuture<?> future;
        final ServerBossBar bossBar;
        volatile int remaining;
        final int total;

        TimerData(ServerBossBar bossBar, int duration) {
            this.bossBar = bossBar;
            this.remaining = duration;
            this.total = duration;
        }
    }

    private record Trigger(String message) {}
    private record TimerConfig(int duration, String bossbarMessage,
                               List<String> beforeCommands,
                               List<String> afterCommands,
                               Map<Integer, Trigger> triggers) {}
}
