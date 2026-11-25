package org.Cry_Legende.sHVisualizer;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Montre aux joueurs autour ce qui se passe dans les blocs, avec des display envoyés par paquets. */
public final class SHVisualizer extends JavaPlugin {

    private volatile Reglages reglages;
    private volatile Textes textes;
    private Moteur moteur;
    private Tables tables;
    private Blocs blocs;
    /** Pourquoi rien n'est affiché, quand le serveur n'expose pas ce qu'il faut ; null si tout va bien. */
    private String panne;
    /** L'expansion PlaceholderAPI, gardée sans son type : la classe n'existe pas sur un serveur sans PlaceholderAPI. */
    private Object expansion;

    @Override
    public void onEnable() {
        for (String fichier : List.of("config.yml", "messages.yml")) {
            if (!new File(getDataFolder(), fichier).exists()) {
                saveResource(fichier, false);
            }
        }
        try {
            reglages = lireReglages();
            textes = new Textes(lireTextes());
        } catch (RuntimeException e) {
            getLogger().severe("Configuration invalide, SHVisualizer s'arrête : " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        Commandes commandes = new Commandes(this);
        for (String nom : List.of("visuels", "shvisualizer")) {
            PluginCommand c = getCommand(nom);
            c.setExecutor(commandes);
            c.setTabCompleter(commandes);
        }
        Pont pont;
        try {
            pont = Pont.creer();
            essayer(pont);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            panne = "cette version du serveur ne se laisse pas lire (" + e + ")";
            getLogger().severe("Aucun affichage : " + panne + ". Le reste du serveur n'est pas touché.");
            return;
        }
        if (!pont.gestePossible() && reglages.joueurs().geste()) {
            getLogger().warning("Le geste du bras (joueurs.geste-du-bras) n'existe pas sous ce nom sur cette version du serveur : il n'est pas montré.");
        }
        moteur = new Moteur(this, pont);
        Effets effets = new Effets(this, moteur);
        tables = new Tables(this, moteur, effets);
        blocs = new Blocs(this, moteur);
        for (Listener l : List.of(moteur, tables, new Conteneurs(this, moteur, effets), blocs, new Notes(this, moteur, effets))) {
            getServer().getPluginManager().registerEvents(l, this);
        }
        Bukkit.getScheduler().runTaskTimer(this, moteur::avancer, 1L, 1L);
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            expansion = new Expansion(this);
            ((Expansion) expansion).register();
        }
    }

    /**
     * Fabrique un display et ses paquets une fois, au démarrage : si une mise à jour du serveur casse quelque chose, on
     * le sait tout de suite et proprement, pas au premier établi ouvert.
     */
    private void essayer(Pont pont) {
        World monde = Bukkit.getWorlds().get(0);
        Affichage a = Affichage.objet(pont, monde, 0, 0, 0);
        Poses.flotter(a.objet(), new ItemStack(Material.STONE), 0, 1f);
        List<Object> paquets = new java.util.ArrayList<>();
        a.apparition(paquets);
        pont.lot(paquets);
        a.objet().setItemStack(new ItemStack(Material.DIRT));
        a.relancer(1);
        if (a.paquetChangements() == null) {
            throw new IllegalStateException("le serveur ne dit pas ce qui change sur une entité");
        }
        pont.retrait(a.id);
    }

    @Override
    public void onDisable() {
        if (expansion != null) {
            ((Expansion) expansion).unregister();
        }
        // les joueurs ne doivent garder aucune entité figée : tout est retiré chez eux avant l'arrêt
        if (tables != null) {
            tables.fermerTout();
        }
        if (moteur != null) {
            moteur.fermerTout();
        }
    }

    /**
     * Recharge config.yml et messages.yml ; un fichier invalide ne change rien et le message dit ce qui cloche. Les
     * fichiers sont lus tout de suite, les nouveaux réglages sont pris au prochain tick, sur le fil principal.
     */
    void recharger(CommandSender sender) {
        Reglages r;
        Textes t;
        try {
            r = lireReglages();
            t = new Textes(lireTextes());
        } catch (RuntimeException e) {
            textes.envoyer(sender, "reload-erreur", "erreur", String.valueOf(e.getMessage()));
            return;
        }
        Runnable appliquer = () -> {
            reglages = r;
            textes = t;
            if (moteur != null) {
                blocs.recharger();
                moteur.toutRevoir();
            }
            t.envoyer(sender, "reload-ok");
        };
        if (moteur == null) {
            appliquer.run();
        } else {
            moteur.plusTard(appliquer);
        }
    }

    void info(CommandSender sender) {
        if (moteur == null) {
            textes.envoyer(sender, "info-arrete", "raison", String.valueOf(panne));
            return;
        }
        // l'état du moteur ne se lit que sur le fil principal
        moteur.plusTard(() -> {
            Textes t = textes;
            int coupes = 0;
            for (Vue v : moteur.vues()) {
                if (v.coupe) {
                    coupes++;
                }
            }
            t.envoyer(sender, "info", "scenes", Integer.toString(moteur.scenes()), "vues", Integer.toString(moteur.montrees()),
                    "joueurs", Integer.toString(moteur.vues().size()), "coupes", Integer.toString(coupes));
            double[] cout = moteur.cout();
            t.envoyer(sender, "info-cout", "moyenne", String.format(Locale.FRENCH, "%.1f", cout[0]), "pire", String.format(Locale.FRENCH, "%.0f", cout[1]));
            for (String[] poste : moteur.detail()) {
                t.envoyer(sender, "info-poste", "poste", poste[0], "moyenne", poste[1], "pire", poste[2]);
            }
            if (moteur.erreurs() > 0) {
                t.envoyer(sender, "info-erreurs", "erreurs", Integer.toString(moteur.erreurs()));
            }
        });
    }

    /** Ce joueur voit-il les affichages en ce moment ? */
    boolean voit(Player p) {
        return moteur == null || !Boolean.TRUE.equals(moteur.coupe(p.getUniqueId()));
    }

    /** null : inconnu, ou le plugin n'affiche rien sur ce serveur. */
    Boolean coupe(java.util.UUID joueur) {
        return moteur == null ? null : moteur.coupe(joueur);
    }

    /** Coupe ou remet les affichages d'un joueur connecté et le lui dit. */
    void regler(Player p, boolean voir) {
        if (moteur != null) {
            moteur.regler(p, voir);
        }
        textes.envoyer(p, voir ? "visuels-actifs" : "visuels-coupes");
    }

    private Reglages lireReglages() {
        Reglages r = Reglages.lire(lire("config.yml"), defauts("config.yml"), getLogger()::warning);
        // ce que seul le serveur sait dire : un bloc qui n'existe pas en objet ne peut pas être montré sur une case
        if (!r.crafter().caseFermee().isItem()) {
            throw new IllegalArgumentException("« crafter.case-fermee » : « " + r.crafter().caseFermee() + " » n'est pas un objet que l'on peut tenir");
        }
        return r;
    }

    private YamlConfiguration lireTextes() {
        YamlConfiguration fichier = lire("messages.yml");
        fichier.setDefaults(defauts("messages.yml"));
        return fichier;
    }

    private YamlConfiguration lire(String nom) {
        YamlConfiguration fichier = new YamlConfiguration();
        try {
            fichier.load(new File(getDataFolder(), nom));
        } catch (Exception e) {
            throw new IllegalArgumentException(nom + " illisible, " + e.getMessage());
        }
        return fichier;
    }

    private YamlConfiguration defauts(String nom) {
        InputStream flux = getResource(nom);
        return flux == null ? new YamlConfiguration() : YamlConfiguration.loadConfiguration(new InputStreamReader(flux, StandardCharsets.UTF_8));
    }

    Reglages reglages() {
        return reglages;
    }

    Textes textes() {
        return textes;
    }
}
