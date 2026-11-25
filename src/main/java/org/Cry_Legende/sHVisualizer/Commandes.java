package org.Cry_Legende.sHVisualizer;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /visuels pour les joueurs, /shvisualizer pour l'administration. */
final class Commandes implements TabExecutor {

    private static final List<String> SOUS_COMMANDES = List.of("reload", "info", "on", "off");

    private final SHVisualizer plugin;

    Commandes(SHVisualizer plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Textes textes = plugin.textes();
        if (command.getName().equals("visuels")) {
            if (!(sender instanceof Player p)) {
                textes.envoyer(sender, "joueur-seulement");
            } else {
                plugin.regler(p, !plugin.voit(p));
            }
            return true;
        }
        String sous = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (!SOUS_COMMANDES.contains(sous) || !sender.hasPermission("shvisualizer.admin." + sous)) {
            textes.envoyer(sender, "usage", "sous-commandes", String.join("|", permises(sender)));
            return true;
        }
        switch (sous) {
            case "reload" -> plugin.recharger(sender);
            case "info" -> plugin.info(sender);
            default -> {
                // on|off <joueur> : ce que fait le menu des paramètres quand le joueur clique
                Player cible = args.length < 2 ? null : Bukkit.getPlayerExact(args[1]);
                if (cible == null) {
                    textes.envoyer(sender, "joueur-absent", "joueur", args.length < 2 ? "?" : args[1]);
                } else {
                    plugin.regler(cible, sous.equals("on"));
                }
            }
        }
        return true;
    }

    private static List<String> permises(CommandSender sender) {
        List<String> out = new ArrayList<>();
        for (String s : SOUS_COMMANDES) {
            if (sender.hasPermission("shvisualizer.admin." + s)) {
                out.add(s);
            }
        }
        return out;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (!command.getName().equals("shvisualizer")) {
            return out;
        }
        if (args.length == 1) {
            for (String s : permises(sender)) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("off"))
                && sender.hasPermission("shvisualizer.admin." + args[0].toLowerCase(Locale.ROOT))) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        }
        return out;
    }
}
