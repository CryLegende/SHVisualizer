package org.Cry_Legende.sHVisualizer;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * %shvisualizer_status% : « enabled » si le joueur voit les affichages, « disabled » s'il les a coupés, vide tant que
 * le plugin ne sait pas (ou n'affiche rien sur ce serveur). Sert au menu des paramètres.
 */
final class Expansion extends PlaceholderExpansion {

    private final SHVisualizer plugin;

    Expansion(SHVisualizer plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "shvisualizer";
    }

    @Override
    public String getAuthor() {
        return "Cry_Legende";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer joueur, String params) {
        if (joueur == null || !params.equalsIgnoreCase("status")) {
            return null;
        }
        Boolean coupe = plugin.coupe(joueur.getUniqueId());
        return coupe == null ? "" : coupe ? "disabled" : "enabled";
    }
}
