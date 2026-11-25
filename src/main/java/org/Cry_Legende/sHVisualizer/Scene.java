package org.Cry_Legende.sHVisualizer;

import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Un groupe d'affichages au même endroit, vus par les mêmes joueurs : les objets d'un établi, la barre d'un spawner, un
 * objet qui vole. Le moteur décide qui la voit ; elle envoie les paquets.
 */
class Scene {

    /** Familles de scènes dont le nombre montré à un joueur peut être plafonné. */
    static final int LIBRE = -1, SPAWNER = 0, CRAFTER = 1, ETIQUETTE = 2, FAMILLES = 3;

    final Moteur moteur;
    final World monde;
    final double x, y, z;
    final double portee;
    final int famille;
    final List<Affichage> parties = new ArrayList<>(4);
    final List<Vue> spectateurs = new ArrayList<>(2);
    /** Scène courte dont les spectateurs sont fixés à la création. */
    boolean figee;
    boolean fermee;
    /**
     * Les objets sont envoyés deux ticks après l'apparition de leur display. false : la scène s'en charge elle-même
     * (un objet qui vole reçoit le sien avec son premier mouvement).
     */
    boolean revele = true;
    /** Ticks entre l'apparition d'un display et l'envoi de son objet : le jeu du joueur a eu le temps de le poser. */
    private static final int REVELATION = 2;

    Scene(Moteur moteur, World monde, double x, double y, double z, double portee, int famille) {
        this.moteur = moteur;
        this.monde = monde;
        this.x = x;
        this.y = y;
        this.z = z;
        this.portee = portee;
        this.famille = famille;
    }

    /** Ajoute un affichage et le montre à ceux qui voient déjà la scène. */
    Affichage ajouter(Affichage a) {
        parties.add(a);
        if (spectateurs.isEmpty()) {
            return a;
        }
        for (Vue v : spectateurs) {
            List<Object> paquets = new ArrayList<>(2);
            apparition(v, a, paquets);
            moteur.envoyer(v, moteur.pont.lot(paquets));
        }
        if (revele && a.differe()) {
            List<Vue> pour = new ArrayList<>(spectateurs);
            moteur.apres(REVELATION, () -> reveler(List.of(a), pour));
        }
        return a;
    }

    /** Envoie leur objet aux displays que ces joueurs viennent de voir apparaître, s'ils les voient toujours. */
    private void reveler(List<Affichage> lesquels, List<Vue> pour) {
        if (fermee) {
            return;
        }
        for (Affichage a : lesquels) {
            if (!parties.contains(a)) {
                continue;
            }
            Object objet = a.revelation();
            if (objet == null) {
                continue;
            }
            for (Vue v : pour) {
                if (spectateurs.contains(v)) {
                    moteur.envoyer(v, objet);
                }
            }
        }
    }

    /** Retire un affichage chez tous ceux qui le voient. */
    void retirer(Affichage a) {
        if (parties.remove(a) && !spectateurs.isEmpty()) {
            Object paquet = moteur.pont.retrait(a.id);
            for (Vue v : spectateurs) {
                moteur.envoyer(v, paquet);
            }
        }
    }

    /** Retire plusieurs affichages d'un coup : un seul message par joueur. */
    void retirer(List<Affichage> plusieurs) {
        int[] ids = new int[plusieurs.size()];
        int n = 0;
        for (Affichage a : plusieurs) {
            if (parties.remove(a)) {
                ids[n++] = a.id;
            }
        }
        if (n > 0 && !spectateurs.isEmpty()) {
            Object paquet = moteur.pont.retrait(n == ids.length ? ids : java.util.Arrays.copyOf(ids, n));
            for (Vue v : spectateurs) {
                moteur.envoyer(v, paquet);
            }
        }
    }

    /** Envoie à ceux qui voient la scène ce qui a changé sur un affichage. */
    void envoyer(Affichage a) {
        Object paquet = a.paquetChangements();
        if (paquet != null) {
            for (Vue v : spectateurs) {
                moteur.envoyer(v, paquet);
            }
        }
    }

    void envoyerTout() {
        for (Affichage a : parties) {
            envoyer(a);
        }
    }

    /** Ajoute à « dans » l'apparition d'un affichage pour ce joueur ; une scène vue de plusieurs endroits la place ici. */
    void apparition(Vue v, Affichage a, List<Object> dans) {
        a.apparition(dans);
    }

    /** Appelée par le moteur : ce joueur commence à voir la scène. Tout part en un message, pour apparaître d'un coup. */
    void montrer(Vue v) {
        if (parties.isEmpty()) {
            return;
        }
        List<Object> paquets = new ArrayList<>(parties.size() * 2);
        List<Affichage> objets = null;
        for (Affichage a : parties) {
            apparition(v, a, paquets);
            if (revele && a.differe()) {
                if (objets == null) {
                    objets = new ArrayList<>(parties.size());
                }
                objets.add(a);
            }
        }
        moteur.envoyer(v, moteur.pont.lot(paquets));
        if (objets != null) {
            List<Affichage> lesquels = objets;
            moteur.apres(REVELATION, () -> reveler(lesquels, List.of(v)));
        }
    }

    /** Appelée par le moteur : ce joueur ne voit plus la scène. Le retrait part toujours, même s'il a changé de monde. */
    void cacher(Vue v) {
        if (parties.isEmpty()) {
            return;
        }
        int[] ids = new int[parties.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = parties.get(i).id;
        }
        moteur.envoyer(v, moteur.pont.retrait(ids));
    }

    /** Ce joueur a-t-il le droit de voir la scène (au-delà de la distance) ? */
    boolean accepte(Vue v) {
        return true;
    }

    /** Appelée par le moteur quand un joueur qui voit déjà la scène s'est déplacé. */
    void suivre(Vue v) {
    }

    /** Plus personne ne la voit : une scène refaite à la demande se ferme ici. */
    void vide() {
    }

    /** La retire chez tout le monde et l'oublie. */
    final void fermer() {
        if (!fermee) {
            fermee = true;
            moteur.oublier(this);
            fin();
        }
    }

    /** Ménage propre à la scène, après sa fermeture. */
    void fin() {
    }

    double distance2(double px, double py, double pz) {
        double dx = px - x, dy = py - y, dz = pz - z;
        return dx * dx + dy * dy + dz * dz;
    }
}
