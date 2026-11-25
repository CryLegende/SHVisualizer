package org.Cry_Legende.sHVisualizer;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

/**
 * Décide qui voit quoi et fait avancer les animations. Tout l'état vit sur le fil principal : les événements (qui
 * arrivent sur le fil de leur monde) ne font que déposer une tâche, exécutée au tick suivant. Rien ne tourne en boucle
 * sur les scènes : un joueur n'est regardé que s'il a bougé, une scène n'envoie que ce qui change.
 */
final class Moteur implements Listener {

    /** Un joueur garde une scène jusqu'à cette distance au-delà de sa portée, pour ne pas clignoter à la limite. */
    private static final double MARGE = 2.0;

    /** Un module qui fabrique ses scènes à la demande, quand un joueur s'en approche. */
    interface Passage {
        void autour(Vue vue, Location ou);
    }

    private record Tache(long tick, long rang, Runnable action) implements Comparable<Tache> {
        @Override
        public int compareTo(Tache o) {
            return tick != o.tick ? Long.compare(tick, o.tick) : Long.compare(rang, o.rang);
        }
    }

    final SHVisualizer plugin;
    final Pont pont;
    private final NamespacedKey cleCoupe;
    private final ConcurrentLinkedQueue<Runnable> file = new ConcurrentLinkedQueue<>();
    private final PriorityQueue<Tache> taches = new PriorityQueue<>();
    private final Map<UUID, Vue> vues = new HashMap<>();
    /** Qui a coupé les affichages, lisible depuis n'importe quel fil (placeholder du menu des paramètres). */
    private final Map<UUID, Boolean> coupes = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Map<Long, List<Scene>>> carte = new HashMap<>();
    /** Les joueurs rangés par monde et par chunk : une scène qui commence trouve ses voisins sans regarder tout le serveur. */
    private final Map<UUID, Map<Long, List<Vue>>> grille = new HashMap<>();
    private final List<Passage> passages = new ArrayList<>();
    private final List<Runnable> chaqueTick = new ArrayList<>();
    private final List<String> postes = new ArrayList<>(List.of("événements", "animations"));
    private final Location tampon = new Location(null, 0, 0, 0);
    private final List<Scene> candidates = new ArrayList<>();
    private final int[] demandes = new int[Scene.FAMILLES];
    private long tick;
    private long rang;
    private int tours;
    private int erreurs;
    /**
     * Temps passé par le plugin sur le fil principal, par poste : la minute en cours, puis la dernière minute pleine.
     * Postes : les événements reçus, les animations en cours, chaque module, puis le passage des joueurs.
     */
    private long[] nanos = new long[2], pire = new long[2];
    private long[] nanosMinute = new long[2], pireMinute = new long[2];
    private long pireTick, pireTickMinute;

    Moteur(SHVisualizer plugin, Pont pont) {
        this.plugin = plugin;
        this.pont = pont;
        this.cleCoupe = new NamespacedKey(plugin, "coupe");
        for (Player p : Bukkit.getOnlinePlayers()) {
            arrivee(p);
        }
    }

    long tick() {
        return tick;
    }

    void passage(Passage p) {
        passages.add(p);
    }

    void chaqueTick(String poste, Runnable r) {
        chaqueTick.add(r);
        postes.add(poste);
    }

    /** Depuis n'importe quel fil : à faire au prochain tick, sur le fil principal. */
    void plusTard(Runnable r) {
        file.add(r);
    }

    /** Sur le fil principal : à faire dans « ticks » ticks. */
    void apres(int ticks, Runnable r) {
        taches.add(new Tache(tick + Math.max(1, ticks), rang++, r));
    }

    void avancer() {
        int n = postes.size() + 1;
        if (nanos.length != n) {
            nanos = new long[n];
            pire = new long[n];
            nanosMinute = new long[n];
            pireMinute = new long[n];
        }
        long debut = System.nanoTime(), t = debut;
        tick++;
        Runnable r;
        while ((r = file.poll()) != null) {
            executer(r);
        }
        t = compter(0, t);
        Tache tache;
        while ((tache = taches.peek()) != null && tache.tick <= tick) {
            taches.poll();
            executer(tache.action);
        }
        t = compter(1, t);
        for (int i = 0; i < chaqueTick.size(); i++) {
            executer(chaqueTick.get(i));
            t = compter(2 + i, t);
        }
        Reglages reg = plugin.reglages();
        int periode = reg.joueurs().periode();
        for (Vue v : vues.values()) {
            if ((tick + v.tour) % periode == 0) {
                try {
                    passer(v, reg);
                } catch (Throwable e) {
                    signaler(e);
                }
            }
        }
        t = compter(n - 1, t);
        pireTick = Math.max(pireTick, t - debut);
        if (tick % 1200 == 0) {
            nanosMinute = nanos;
            pireMinute = pire;
            pireTickMinute = pireTick;
            nanos = new long[n];
            pire = new long[n];
            pireTick = 0;
        }
    }

    private long compter(int poste, long depuis) {
        long t = System.nanoTime(), duree = t - depuis;
        nanos[poste] += duree;
        if (duree > pire[poste]) {
            pire[poste] = duree;
        }
        return t;
    }

    private void executer(Runnable r) {
        try {
            r.run();
        } catch (Throwable e) {
            signaler(e);
        }
    }

    /** Une erreur d'affichage ne doit jamais gêner le jeu : elle est comptée, les dix premières avec leur détail. */
    void signaler(Throwable e) {
        if (erreurs++ < 10) {
            plugin.getLogger().log(Level.WARNING, "Erreur d'affichage", e);
        }
    }

    /** Erreurs d'affichage depuis le chargement du plugin. */
    int erreurs() {
        return erreurs;
    }

    // ------------------------------------------------------------------ joueurs

    private void arrivee(Player p) {
        boolean coupe = p.getPersistentDataContainer().has(cleCoupe, PersistentDataType.BYTE)
                ? p.getPersistentDataContainer().get(cleCoupe, PersistentDataType.BYTE) != 0
                : !plugin.reglages().joueurs().actifParDefaut();
        coupes.put(p.getUniqueId(), coupe);
        Vue ancienne = vues.put(p.getUniqueId(), new Vue(p, coupe, tours++));
        if (ancienne != null) {
            detacher(ancienne, false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        plusTard(() -> {
            if (p.isOnline()) {
                arrivee(p);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        Player p = e.getPlayer();
        plusTard(() -> {
            Vue v = vues.get(id);
            if (v != null && v.joueur == p) {
                vues.remove(id);
                coupes.remove(id);
                detacher(v, false);
            }
        });
    }

    /** Le client oublie toutes ses entités en changeant de monde : on les lui retire quand même, puis on repart de zéro. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        plusTard(() -> {
            Vue v = vues.get(id);
            if (v != null) {
                detacher(v, true);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnload(WorldUnloadEvent e) {
        World w = e.getWorld();
        plusTard(() -> {
            grille.remove(w.getUID());
            Map<Long, List<Scene>> m = carte.remove(w.getUID());
            if (m != null) {
                List<Scene> toutes = new ArrayList<>();
                m.values().forEach(toutes::addAll);
                toutes.forEach(Scene::fermer);
            }
        });
    }

    Vue vue(Player p) {
        return vues.get(p.getUniqueId());
    }

    Collection<Vue> vues() {
        return vues.values();
    }

    /** true : ce joueur a coupé les affichages ; null : pas encore connu (il vient d'arriver). */
    Boolean coupe(UUID joueur) {
        return coupes.get(joueur);
    }

    /**
     * Coupe ou remet les affichages d'un joueur connecté, et le garde pour ses prochaines connexions. La commande peut
     * venir de n'importe quel fil (le bouton d'un menu) : le choix est noté tout de suite, ce que le joueur voit change
     * au prochain tick, sur le fil principal.
     */
    void regler(Player p, boolean voir) {
        p.getPersistentDataContainer().set(cleCoupe, PersistentDataType.BYTE, (byte) (voir ? 0 : 1));
        UUID id = p.getUniqueId();
        coupes.put(id, !voir);
        plusTard(() -> {
            Vue v = vues.get(id);
            if (v != null && v.joueur == p && v.coupe == voir) {
                v.coupe = !voir;
                v.aRevoir = true;
                if (v.coupe) {
                    detacher(v, true);
                }
            }
        });
    }

    /** Tous les joueurs seront repassés : après un rechargement des réglages. */
    void toutRevoir() {
        for (Vue v : vues.values()) {
            v.aRevoir = true;
        }
    }

    void envoyer(Vue v, Object paquet) {
        if (v.connexion == null) {
            v.connexion = pont.connexion(v.joueur);
            if (v.connexion == null) {
                return;
            }
        }
        pont.envoyer(v.connexion, paquet);
    }

    private void ranger(Vue v, World w, int cx, int cz) {
        long cle = Chunk.getChunkKey(cx, cz);
        UUID monde = w.getUID();
        if (v.rangement != null && v.rangementCle == cle && monde.equals(v.rangementMonde)) {
            return;
        }
        deranger(v);
        List<Vue> c = grille.computeIfAbsent(monde, k -> new HashMap<>()).computeIfAbsent(cle, k -> new ArrayList<>(2));
        c.add(v);
        v.rangement = c;
        v.rangementMonde = monde;
        v.rangementCle = cle;
    }

    /** Sort le joueur de sa case ; une case vide quitte la grille, qui ne garde ainsi que les chunks occupés. */
    private void deranger(Vue v) {
        List<Vue> c = v.rangement;
        if (c == null) {
            return;
        }
        c.remove(v);
        v.rangement = null;
        if (c.isEmpty()) {
            Map<Long, List<Vue>> m = grille.get(v.rangementMonde);
            if (m != null && m.get(v.rangementCle) == c) {
                m.remove(v.rangementCle);
                if (m.isEmpty()) {
                    grille.remove(v.rangementMonde);
                }
            }
        }
    }

    /** Le joueur ne voit plus rien ; « paquets » : lui envoyer les retraits (inutile s'il est parti). */
    private void detacher(Vue v, boolean paquets) {
        deranger(v);
        if (!v.scenes.isEmpty()) {
            for (Scene s : new ArrayList<>(v.scenes)) {
                if (paquets) {
                    s.cacher(v);
                }
                s.spectateurs.remove(v);
                if (s.spectateurs.isEmpty() && !s.fermee) {
                    s.vide();
                }
            }
            v.scenes.clear();
        }
        java.util.Arrays.fill(v.compte, 0);
        v.monde = null;
        v.aRevoir = true;
    }

    private void passer(Vue v, Reglages reg) {
        Player p = v.joueur;
        if (!p.isOnline()) {
            return;
        }
        Location l = p.getLocation(tampon);
        World w = l.getWorld();
        if (w == null) {
            return;
        }
        if (v.coupe || reg.joueurs().mondesIgnores().contains(w.getName())) {
            if (!v.scenes.isEmpty()) {
                detacher(v, true);
            }
            return;
        }
        boolean ailleurs = !w.getUID().equals(v.monde);
        if (!ailleurs && !v.aRevoir) {
            double dx = l.getX() - v.px, dy = l.getY() - v.py, dz = l.getZ() - v.pz;
            if (dx * dx + dy * dy + dz * dz < 1.0) {
                return;
            }
        }
        v.monde = w.getUID();
        v.px = l.getX();
        v.py = l.getY();
        v.pz = l.getZ();
        v.aRevoir = false;
        ranger(v, w, l.getBlockX() >> 4, l.getBlockZ() >> 4);
        for (Passage ps : passages) {
            try {
                ps.autour(v, l);
            } catch (RuntimeException e) {
                // un module en panne n'empêche pas le joueur de quitter et de rejoindre les autres scènes
                signaler(e);
            }
        }
        if (!v.scenes.isEmpty()) {
            Iterator<Scene> it = v.scenes.iterator();
            while (it.hasNext()) {
                Scene s = it.next();
                if (s.figee) {
                    continue;
                }
                double limite = s.portee + MARGE;
                if (s.monde != w || s.distance2(v.px, v.py, v.pz) > limite * limite || !s.accepte(v)) {
                    it.remove();
                    quitter(s, v);
                } else {
                    s.suivre(v);
                }
            }
        }
        Map<Long, List<Scene>> m = carte.get(w.getUID());
        if (m == null || m.isEmpty()) {
            return;
        }
        int cx = l.getBlockX() >> 4, cz = l.getBlockZ() >> 4, rayon = reg.rayonChunks();
        candidates.clear();
        int[] demandes = this.demandes;
        java.util.Arrays.fill(demandes, 0);
        for (int dx = -rayon; dx <= rayon; dx++) {
            for (int dz = -rayon; dz <= rayon; dz++) {
                long cle = Chunk.getChunkKey(cx + dx, cz + dz);
                List<Scene> ici = m.get(cle);
                if (ici == null) {
                    continue;
                }
                boolean recu = p.isChunkSent(cle);
                for (int i = 0; i < ici.size(); i++) {
                    Scene s = ici.get(i);
                    if (s.distance2(v.px, v.py, v.pz) > s.portee * s.portee || v.scenes.contains(s) || !s.accepte(v)) {
                        continue;
                    }
                    if (!recu) {
                        // le décor arrive avant ce qui est posé dessus
                        v.aRevoir = true;
                    } else if (s.famille < 0) {
                        rejoindre(s, v);
                    } else if (!plein(v, s, reg)) {
                        candidates.add(s);
                        demandes[s.famille]++;
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        // une famille plafonnée qui déborde : les scènes les plus proches passent d'abord
        boolean deborde = false;
        for (int f = 0; f < demandes.length; f++) {
            deborde |= v.compte[f] + demandes[f] > reg.plafond(f);
        }
        if (deborde) {
            candidates.sort((a, b) -> Double.compare(a.distance2(v.px, v.py, v.pz), b.distance2(v.px, v.py, v.pz)));
        }
        for (int i = 0; i < candidates.size(); i++) {
            Scene s = candidates.get(i);
            if (!plein(v, s, reg)) {
                rejoindre(s, v);
            }
        }
        candidates.clear();
    }

    private static boolean plein(Vue v, Scene s, Reglages reg) {
        return s.famille >= 0 && v.compte[s.famille] >= reg.plafond(s.famille);
    }

    private void rejoindre(Scene s, Vue v) {
        if (v.scenes.add(s)) {
            s.spectateurs.add(v);
            if (s.famille >= 0) {
                v.compte[s.famille]++;
            }
            s.montrer(v);
        }
    }

    /** Le joueur a déjà été retiré de la liste de la vue par l'appelant. */
    private void quitter(Scene s, Vue v) {
        s.spectateurs.remove(v);
        if (s.famille >= 0 && v.compte[s.famille] > 0) {
            v.compte[s.famille]--;
        }
        s.cacher(v);
        if (s.spectateurs.isEmpty() && !s.fermee) {
            s.vide();
        }
    }

    // ------------------------------------------------------------------ scènes

    private static long cle(Scene s) {
        return Chunk.getChunkKey((int) Math.floor(s.x) >> 4, (int) Math.floor(s.z) >> 4);
    }

    private void enregistrer(Scene s) {
        carte.computeIfAbsent(s.monde.getUID(), k -> new HashMap<>()).computeIfAbsent(cle(s), k -> new ArrayList<>(2)).add(s);
    }

    /** Les joueurs rangés dans les chunks autour d'une scène, à sa portée ou non. */
    private List<Vue> voisins(Scene s) {
        List<Vue> proches = new ArrayList<>();
        Map<Long, List<Vue>> m = grille.get(s.monde.getUID());
        if (m == null || m.isEmpty()) {
            return proches;
        }
        // un joueur est rangé là où il était à son dernier passage : un bloc de marge
        int rayon = (int) Math.ceil((s.portee + 1) / 16.0);
        int cx = (int) Math.floor(s.x) >> 4, cz = (int) Math.floor(s.z) >> 4;
        for (int dx = -rayon; dx <= rayon; dx++) {
            for (int dz = -rayon; dz <= rayon; dz++) {
                List<Vue> ici = m.get(Chunk.getChunkKey(cx + dx, cz + dz));
                if (ici != null) {
                    proches.addAll(ici);
                }
            }
        }
        return proches;
    }

    /** Scène durable : montrée tout de suite aux joueurs à portée, puis suivie au fil de leurs déplacements. */
    void inscrire(Scene s) {
        enregistrer(s);
        Reglages reg = plugin.reglages();
        for (Vue v : voisins(s)) {
            if (visible(s, v, reg) && !plein(v, s, reg)) {
                rejoindre(s, v);
            }
        }
    }

    /**
     * Scène durable faite pour un joueur qui s'en approche : lui seul la rejoint tout de suite. Les autres joueurs à
     * portée l'auraient déjà fait exister ; ceux qui arrivent la trouveront à leur passage.
     */
    void inscrire(Scene s, Vue pour) {
        enregistrer(s);
        Reglages reg = plugin.reglages();
        if (visible(s, pour, reg) && !plein(pour, s, reg)) {
            rejoindre(s, pour);
        }
    }

    /** Ce joueur voit-il déjà autant de scènes de cette famille que permis ? */
    boolean plein(Vue v, int famille) {
        return famille >= 0 && v.compte[famille] >= plugin.reglages().plafond(famille);
    }

    /** Scène courte : montrée aux joueurs à portée maintenant, sans suivi ensuite. */
    void jouer(Scene s) {
        s.figee = true;
        Reglages reg = plugin.reglages();
        for (Vue v : voisins(s)) {
            if (visible(s, v, reg)) {
                rejoindre(s, v);
            }
        }
    }

    /** Scène courte montrée à des joueurs choisis : ceux qui voyaient la scène dont elle prend la suite. */
    void jouer(Scene s, Collection<Vue> a) {
        s.figee = true;
        for (Vue v : new ArrayList<>(a)) {
            if (vues.get(v.joueur.getUniqueId()) == v) {
                rejoindre(s, v);
            }
        }
    }

    /**
     * Scène courte qui prend la suite d'une autre : mêmes spectateurs, et rien n'est renvoyé, car ses affichages viennent
     * de la première et sont déjà chez eux. Une réapparition couperait le glissement qui suit.
     */
    void heriter(Scene s, Scene source) {
        s.figee = true;
        for (Vue v : source.spectateurs) {
            if (v.scenes.add(s)) {
                s.spectateurs.add(v);
            }
        }
    }

    private boolean visible(Scene s, Vue v, Reglages reg) {
        if (v.coupe || !v.joueur.isOnline()) {
            return false;
        }
        Location l = v.joueur.getLocation(tampon);
        return l.getWorld() == s.monde && !reg.joueurs().mondesIgnores().contains(s.monde.getName())
                && s.distance2(l.getX(), l.getY(), l.getZ()) <= s.portee * s.portee && s.accepte(v);
    }

    /** Appelée par {@link Scene#fermer()}. */
    void oublier(Scene s) {
        if (!s.figee) {
            Map<Long, List<Scene>> m = carte.get(s.monde.getUID());
            if (m != null) {
                List<Scene> ici = m.get(cle(s));
                if (ici != null) {
                    ici.remove(s);
                    if (ici.isEmpty()) {
                        m.remove(cle(s));
                    }
                }
            }
        }
        for (Vue v : s.spectateurs) {
            if (v.scenes.remove(s)) {
                if (s.famille >= 0 && v.compte[s.famille] > 0) {
                    v.compte[s.famille]--;
                }
                s.cacher(v);
            }
        }
        s.spectateurs.clear();
    }

    /** Ferme tout : à l'arrêt du plugin, pour ne laisser aucune entité figée chez les joueurs. */
    void fermerTout() {
        List<Scene> toutes = new ArrayList<>();
        for (Map<Long, List<Scene>> m : carte.values()) {
            m.values().forEach(toutes::addAll);
        }
        for (Vue v : vues.values()) {
            toutes.addAll(v.scenes);
        }
        toutes.forEach(Scene::fermer);
        carte.clear();
        taches.clear();
        file.clear();
    }

    // ------------------------------------------------------------------ état, pour /shvisualizer info

    int scenes() {
        int n = 0;
        for (Map<Long, List<Scene>> m : carte.values()) {
            for (List<Scene> l : m.values()) {
                n += l.size();
            }
        }
        return n;
    }

    int montrees() {
        int n = 0;
        for (Vue v : vues.values()) {
            n += v.scenes.size();
        }
        return n;
    }

    /** Microsecondes par tick sur la dernière minute pleine : moyenne, puis pire tick. */
    double[] cout() {
        long somme = 0;
        for (long v : nanosMinute) {
            somme += v;
        }
        return new double[] {somme / 1200.0 / 1000.0, pireTickMinute / 1000.0};
    }

    /** Le même par poste : nom, moyenne et pire en microsecondes. */
    List<String[]> detail() {
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < nanosMinute.length; i++) {
            String nom = i < postes.size() ? postes.get(i) : "joueurs";
            out.add(new String[] {nom, String.format(java.util.Locale.FRENCH, "%.1f", nanosMinute[i] / 1200.0 / 1000.0),
                    String.format(java.util.Locale.FRENCH, "%.0f", pireMinute[i] / 1000.0)});
        }
        return out;
    }
}
