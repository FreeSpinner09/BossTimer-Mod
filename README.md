# BossTimerMod v2.0.1 🎉

Hey! I’m excited to share **BossTimerMod v2**, a Fabric mod that lets you create and manage server timers with boss bars, messages, and command triggers. Perfect for events like restarts, boss fights, or scheduled server actions.  

---

## 🌨 Features
- `/bossbartimer` command to manage timers.
- Fully configurable timer durations, messages, and triggers.
- **Before and After commands** for custom actions.
- Boss bar display for all players, with countdown.
- Tab completion for timer names.
- Safe reload: cancels running timers before loading config.
- Works with Fabric 1.21.1 and LuckPerms permissions.

---

## 💻 Installation

1. Make sure you have **Fabric Loader 0.16.10+** and **Fabric API 0.116.1+** installed for Minecraft 1.21.1.
2. Download the mod `.jar` (example):
   ```
   bossbartimer-2.0.1-1.21.1.jar
   ```
3. Place the `.jar` in your `mods` folder.
4. Start the server or client.

---

## 📔 Example Configuration

Create a file at `config/bosstimer_commands.json`:

```json
{
  "restart": {
    "duration": 60,
    "bossbar_message": "🕳️ Restart in %s seconds",
    "before": [
      "tellraw @a {\"text\":\"[ALERT] \",\"color\":\"red\",\"bold\":true,\"extra\":[{\"text\":\"An unplanned server restart is about to take place. Please plan accordingly.\",\"color\":\"yellow\"}]}"
      ,"title @a title {\"text\":\"Restart Incoming!\",\"color\":\"gold\"}"
    ],
    "after": [
      "tellraw @a {\"text\":\"[ALERT] \",\"color\":\"yellow\",\"bold\":true,\"extra\":[{\"text\":\"The server is restarting soon.\",\"color\":\"yellow\"}]}"
      ,"execute as @a run stopbattle @s"
    ],
    "triggers": {
      "30": { "message": "🕳️ 30 seconds remaining." },
      "10": { "message": "🕐 10 seconds!" },
      "5": { "message": "⚠️ Restarting soon..." }
    }
  }
}
```

- `duration` → timer length in seconds.  
- `bossbar_message` → shows in the boss bar, `%s` replaced by remaining seconds.  
- `before` → commands executed at the start of the timer.  
- `after` → commands executed after the timer ends.  
- `triggers` → optional messages at specific seconds.

---

## ⚡ Commands

- **Start a timer:**
  ```
  /bossbartimer start <timerName>
  ```
- **Cancel all timers:**
  ```
  /bossbartimer cancel
  ```
- **Reload configuration:**
  ```
  /bossbartimer reload
  ```

---

## 🔑 Permissions

- `bossbartimer.run` → Start or cancel any timer.
- `bossbartimer.reload` → Reload configuration safely.

*Use LuckPerms or another permissions plugin to assign these to your staff.*

---

## 📌 Supported Versions

| Mod Version | Minecraft | Fabric Loader | Fabric API |
|-------------|-----------|--------------|------------|
| 2.0.1       | 1.21.1   | 0.16.10+     | 0.116.1+  |

---

## 🎉 Credits
Created and maintained by **Elijah Partney**.

---

## 💡 Tips
- Test timers on a small server first to make sure commands and messages are correct.  
- Always include `%s` in `bossbar_message` to show countdown.  
- Use triggers to alert players before the timer ends for critical events.