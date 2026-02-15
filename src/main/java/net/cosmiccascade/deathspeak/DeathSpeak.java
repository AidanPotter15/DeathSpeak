package net.cosmiccascade.deathspeak;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import net.kyori.adventure.text.Component;


import java.util.LinkedList;
import java.util.Queue;

public final class DeathSpeak extends JavaPlugin {

    private final Queue<String> wordQueue = new LinkedList<>();
    private boolean isProcessing = false;

    @Override
    public void onEnable() {
        getLogger().info("DeathSpeak has Awakened and is running");

        //register command
        var command = this.getCommand("deathspeak");

        if (command != null) {
            command.setExecutor((sender, cmd, label, args) -> {

                if (args.length == 0) {
                    sender.sendMessage("Usage: /deathspeak <word>");
                    return true;
                }

                String word = args[0].toLowerCase();
                wordQueue.add(word);

                broadcast("\"" + word + "\" has been spoken...");

                processQueue();

                return true;
            });
        } else {
            getLogger().severe("Command 'deathspeak' not found in plugin.yml!");
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("DeathSpeak has died. You killed it. ");
    }

    private void processQueue(){
        if (isProcessing) return;

        String word = wordQueue.poll();
        if(word == null) return;

        isProcessing = true;

        broadcast("§c[DeathSpeak] §7The curse begins in 5 seconds...");

        new BukkitRunnable() {
            int countdown = 5;

            @Override
            public void run(){
                if(countdown <= 0){
                    broadcast("§4[DeathSpeak] §c\"" + word + "\" takes effect.");

                    //deletion logic goes here

                    isProcessing = false;
                    processQueue();
                    cancel();
                    return;
                }

                broadcast("§8[DeathSpeak] §7" + countdown + "...");
                countdown--;
            }
        }.runTaskTimer(this,0L,20L);
    }

    private void broadcast(String message) {
        Bukkit.getServer().broadcast(Component.text(message));
    }

}
