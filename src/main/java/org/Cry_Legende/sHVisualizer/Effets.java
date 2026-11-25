package org.Cry_Legende.sHVisualizer;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.concurrent.ThreadLocalRandom;

/** Ce que plusieurs modules partagent : qui ne doit rien montrer, l'objet qui vole, les particules et les sons. */
final class Effets {

    private final SHVisualizer plugin;
    private final Moteur moteur;
    private Plugin openInv;
    private Method silence;
    private boolean openInvCherche;

    Effets(SHVisualizer plugin, Moteur moteur) {
        this.plugin = plugin;
        this.moteur = moteur;
    }

    /**
     * Un joueur dont les gestes ne doivent rien trahir : spectateur, invisible (les plugins de vanish posent tous la
     * marque « vanished »), ou qui ouvre les coffres en silence avec OpenInv.
     */
    boolean discret(Player p) {
        if (p.getGameMode() == GameMode.SPECTATOR) {
            return true;
        }
        for (MetadataValue v : p.getMetadata("vanished")) {
            if (v.asBoolean()) {
                return true;
            }
        }
        return silencieux(p);
    }

    private boolean silencieux(Player p) {
        if (!openInvCherche) {
            openInvCherche = true;
            openInv = Bukkit.getPluginManager().getPlugin("OpenInv");
            if (openInv != null) {
                for (String nom : new String[] {"getSilentContainerStatus", "getPlayerSilentChestStatus"}) {
                    try {
                        silence = openInv.getClass().getMethod(nom, OfflinePlayer.class);
                        break;
                    } catch (NoSuchMethodException ignored) {
                        // l'autre nom, selon la version d'OpenInv
                    }
                }
            }
        }
        if (silence == null || !openInv.isEnabled()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(silence.invoke(openInv, p));
        } catch (ReflectiveOperationException | RuntimeException e) {
            silence = null;
            return false;
        }
    }

    /** Le jeu dessine-t-il cet objet comme une image plate ? Tout ce qui n'est pas un bloc, et les blocs de la liste. */
    boolean aPlat(ItemStack objet) {
        return !objet.getType().isBlock() || plugin.reglages().objets().aPlat().contains(objet.getType());
    }

    /** Point visé sur un joueur par un objet qui vole vers lui. */
    double[] cible(Player p) {
        Location l = p.getLocation();
        return new double[] {l.getX(), l.getY() + plugin.reglages().objets().vol().hauteurJoueur(), l.getZ()};
    }

    void particules(Scene s, Particle particule, double x, double y, double z, int quantite, double etalement, double vitesse, Object donnee) {
        if (quantite <= 0) {
            return;
        }
        try {
            for (Vue v : s.spectateurs) {
                v.joueur.spawnParticle(particule, x, y, z, quantite, etalement, etalement, etalement, vitesse, donnee);
            }
        } catch (RuntimeException e) {
            // des particules en moins, mais l'animation qui les lance va jusqu'au bout
            moteur.signaler(e);
        }
    }

    /** Le petit bruit d'un objet ramassé, pour ceux qui voient la scène. */
    void sonArrivee(Scene s, double x, double y, double z) {
        Reglages.Vol vol = plugin.reglages().objets().vol();
        if (vol.son().isBlank() || vol.volume() <= 0) {
            return;
        }
        float min = Math.min(vol.tonMin(), vol.tonMax()), max = Math.max(vol.tonMin(), vol.tonMax());
        float ton = min + ThreadLocalRandom.current().nextFloat() * (max - min);
        Location ou = new Location(s.monde, x, y, z);
        try {
            for (Vue v : s.spectateurs) {
                v.joueur.playSound(ou, vol.son(), SoundCategory.PLAYERS, vol.volume(), ton);
            }
        } catch (RuntimeException e) {
            moteur.signaler(e);
        }
    }

    /**
     * Un objet vole d'un point à un autre, pour les joueurs à portée du « centre ». Le jeu du joueur fait tout le trajet
     * à partir d'un seul message, envoyé deux ticks après l'apparition : un display tout juste apparu saute à sa pose
     * sans glisser.
     *
     * @param versJoueur true : du bloc vers le joueur ; false : du joueur vers le bloc. Du côté du joueur l'objet est
     *                   petit : vu de ses propres yeux, à quelques centimètres, il remplirait l'écran.
     */
    void vol(World monde, ItemStack objet, double dx, double dy, double dz, double ax, double ay, double az,
             double cx, double cy, double cz, double portee, boolean son, boolean versJoueur) {
        Scene scene = new Scene(moteur, monde, cx, cy, cz, portee, Scene.LIBRE);
        // l'objet part avec le premier mouvement : il n'attend pas immobile à son point de départ
        scene.revele = false;
        moteur.jouer(scene);
        if (scene.spectateurs.isEmpty()) {
            scene.fermer();
            return;
        }
        // personne d'autre ne suit cette scène : quoi qu'il arrive en route, elle se ferme
        try {
            Reglages.Vol vol = plugin.reglages().objets().vol();
            float angle = (float) (ThreadLocalRandom.current().nextFloat() * Math.PI * 2);
            Affichage a = Affichage.objet(moteur.pont, monde, dx, dy, dz);
            float depart = versJoueur ? vol.taille() : vol.tailleJoueur(), arrivee = versJoueur ? vol.tailleJoueur() : vol.taille();
            Poses.flotter(a.objet(), objet, angle, depart);
            scene.ajouter(a);
            moteur.apres(2, () -> {
                if (scene.fermee) {
                    return;
                }
                try {
                    a.display().setTransformation(Poses.flottant((float) (ax - dx), (float) (ay - dy), (float) (az - dz),
                            angle + (float) Math.toRadians(vol.tour()), arrivee));
                    a.relancer(vol.duree());
                    scene.envoyer(a);
                } catch (RuntimeException e) {
                    scene.fermer();
                    throw e;
                }
                moteur.apres(vol.duree(), () -> {
                    try {
                        if (son && !scene.fermee) {
                            sonArrivee(scene, ax, ay, az);
                        }
                    } finally {
                        scene.fermer();
                    }
                });
            });
        } catch (RuntimeException e) {
            scene.fermer();
            throw e;
        }
    }

    /**
     * Un affichage flottant déjà montré s'envole vers un joueur, puis sa scène se ferme.
     *
     * @param angle l'angle qu'il a en ce moment
     */
    void envol(Scene scene, Affichage a, float angle, Player vers, boolean son) {
        Reglages.Vol vol = plugin.reglages().objets().vol();
        if (scene.fermee || !vers.isOnline() || vers.getWorld() != scene.monde) {
            scene.fermer();
            return;
        }
        double[] c = cible(vers);
        try {
            a.display().setTransformation(Poses.flottant((float) (c[0] - a.x), (float) (c[1] - a.y), (float) (c[2] - a.z),
                    angle + (float) Math.toRadians(vol.tour()), vol.tailleJoueur()));
            a.relancer(vol.duree());
            scene.envoyer(a);
        } catch (RuntimeException e) {
            scene.fermer();
            throw e;
        }
        moteur.apres(vol.duree(), () -> {
            try {
                if (son && !scene.fermee) {
                    sonArrivee(scene, c[0], c[1], c[2]);
                }
            } finally {
                scene.fermer();
            }
        });
    }
}
