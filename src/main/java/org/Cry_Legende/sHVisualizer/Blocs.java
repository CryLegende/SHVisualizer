package org.Cry_Legende.sHVisualizer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Banner;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.Jukebox;
import org.bukkit.block.data.Rotatable;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Les blocs qui montrent quelque chose sans qu'on les ouvre : barre du spawner, nom de la bannière, disque du jukebox,
 * grille du crafter. Ils ne sont cherchés que dans les chunks proches d'un joueur, leur scène n'existe que tant qu'un
 * joueur la voit, et seule une scène vue relit son bloc de temps en temps.
 */
final class Blocs implements Listener, Moteur.Passage {

    enum Genre {SPAWNER, BANNIERE, JUKEBOX, CRAFTER}

    private final class Entree {
        final World monde;
        final int x, y, z;
        final Genre genre;
        SceneBloc scene;
        /** Pas de scène à refaire avant ce tick : bannière sans nom, crafter couché, plafond du joueur atteint. */
        long retenter;
        /** Distance au joueur dont on fait le passage, le temps de ce passage. */
        double distance;

        Entree(World monde, int x, int y, int z, Genre genre) {
            this.monde = monde;
            this.x = x;
            this.y = y;
            this.z = z;
            this.genre = genre;
        }
    }

    private static final class Tranche {
        final Map<Long, Entree> entrees = new LinkedHashMap<>();
        long lue;
    }

    private final SHVisualizer plugin;
    private final Moteur moteur;
    private final Map<UUID, Map<Long, Tranche>> mondes = new HashMap<>();
    private final List<SceneBloc> actives = new ArrayList<>();
    private final List<Entree> aFaire = new ArrayList<>();
    /**
     * Paires de rectangles d'anciennes barres, déjà réglées : une barre qui apparaît en reprend une au lieu d'en refaire.
     * Rangées par monde : une entité garde le monde où elle a été fabriquée.
     */
    private final Map<UUID, java.util.ArrayDeque<Affichage[]>> reserve = new HashMap<>();
    private Places positions;

    /** Les positions d'une barre autour de son bloc, calculées une fois pour tous les spawners. */
    private static final class Places {
        final double[] cos, sin;
        final double pas, proche, loin;

        Places(Reglages.Barre r) {
            int n = r.positions();
            pas = Math.PI * 2 / n;
            cos = new double[n];
            sin = new double[n];
            for (int i = 0; i < n; i++) {
                cos[i] = Math.cos(i * pas);
                sin[i] = Math.sin(i * pas);
            }
            // de près, la barre change de place dès que le joueur s'en écarte ; de loin, seulement s'il passe de côté
            proche = Math.cos(pas * 0.7);
            loin = Math.min(proche, Math.cos(Math.toRadians(85)));
        }
    }
    /** Blocs suivis, pour écarter d'un coup d'œil tous les autres dans les événements de casse et de pose. */
    private volatile Set<Material> suivis = EnumSet.noneOf(Material.class);

    Blocs(SHVisualizer plugin, Moteur moteur) {
        this.plugin = plugin;
        this.moteur = moteur;
        moteur.passage(this);
        moteur.chaqueTick("blocs", this::tic);
        recharger();
    }

    /** Après un changement de réglages : tout est refait à la prochaine approche d'un joueur. */
    void recharger() {
        for (SceneBloc s : new ArrayList<>(actives)) {
            s.fermer();
        }
        mondes.clear();
        reserve.clear();
        Reglages reg = plugin.reglages();
        positions = new Places(reg.spawner().barre());
        Set<Material> s = EnumSet.noneOf(Material.class);
        if (reg.spawner().actif()) {
            s.add(Material.SPAWNER);
        }
        if (reg.jukebox().actif()) {
            s.add(Material.JUKEBOX);
        }
        if (reg.crafter().actif()) {
            s.add(Material.CRAFTER);
        }
        if (reg.banniere().actif()) {
            s.addAll(Tag.BANNERS.getValues());
        }
        suivis = s;
    }

    int connus() {
        int n = 0;
        for (Map<Long, Tranche> m : mondes.values()) {
            for (Tranche t : m.values()) {
                n += t.entrees.size();
            }
        }
        return n;
    }

    private static Genre genre(Material m) {
        return switch (m) {
            case SPAWNER -> Genre.SPAWNER;
            case JUKEBOX -> Genre.JUKEBOX;
            case CRAFTER -> Genre.CRAFTER;
            default -> Genre.BANNIERE;
        };
    }

    private static long cle(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (y & 0xFFFL);
    }

    private static double portee(Genre g, Reglages reg) {
        return switch (g) {
            case SPAWNER -> reg.spawner().portee();
            case BANNIERE -> reg.banniere().portee();
            case JUKEBOX -> reg.jukebox().portee();
            case CRAFTER -> reg.crafter().portee();
        };
    }

    // ------------------------------------------------------------------ recherche autour d'un joueur

    @Override
    public void autour(Vue vue, Location ou) {
        Reglages reg = plugin.reglages();
        double max = reg.porteeBlocs();
        if (max <= 0 || suivis.isEmpty()) {
            return;
        }
        World w = ou.getWorld();
        int cx = ou.getBlockX() >> 4, cz = ou.getBlockZ() >> 4, rayon = (int) Math.ceil(max / 16.0);
        Map<Long, Tranche> m = mondes.computeIfAbsent(w.getUID(), k -> new HashMap<>());
        long t = moteur.tick();
        for (int dx = -rayon; dx <= rayon; dx++) {
            for (int dz = -rayon; dz <= rayon; dz++) {
                int x = cx + dx, z = cz + dz;
                if (!w.isChunkLoaded(x, z)) {
                    // pas encore chargé (arrivée du joueur) : on repassera, même s'il ne bouge plus
                    vue.aRevoir = true;
                    continue;
                }
                long c = Chunk.getChunkKey(x, z);
                Tranche tr = m.get(c);
                if (tr == null || t - tr.lue > reg.relecture()) {
                    tr = lire(w, x, z, tr);
                    m.put(c, tr);
                }
                if (tr.entrees.isEmpty()) {
                    continue;
                }
                for (Entree e : tr.entrees.values()) {
                    if (e.scene != null || t < e.retenter || !pourLui(e.genre, vue, reg)) {
                        continue;
                    }
                    double p = portee(e.genre, reg);
                    e.distance = distance2(e, vue);
                    if (e.distance <= p * p) {
                        aFaire.add(e);
                    }
                }
            }
        }
        if (aFaire.isEmpty()) {
            return;
        }
        // plus de blocs à montrer que le joueur n'en verra : les plus proches d'abord
        if (aFaire.size() > 8) {
            aFaire.sort((a, b) -> Double.compare(a.distance, b.distance));
        }
        for (int i = 0; i < aFaire.size(); i++) {
            Entree e = aFaire.get(i);
            if (e.scene == null && pourLui(e.genre, vue, reg)) {
                creer(e, reg, t, vue);
            }
        }
        aFaire.clear();
    }

    private static double distance2(Entree e, Vue vue) {
        double ex = e.x + 0.5 - vue.px, ey = e.y + 0.5 - vue.py, ez = e.z + 0.5 - vue.pz;
        return ex * ex + ey * ey + ez * ez;
    }

    /** Une scène ne se fabrique que pour un joueur qui pourra la voir : pas s'il en voit déjà autant que permis. */
    private boolean pourLui(Genre g, Vue vue, Reglages reg) {
        return switch (g) {
            case SPAWNER -> !moteur.plein(vue, Scene.SPAWNER) && (reg.spawner().bedrock() || !vue.bedrock);
            case CRAFTER -> !moteur.plein(vue, Scene.CRAFTER);
            default -> !moteur.plein(vue, Scene.ETIQUETTE);
        };
    }

    /** Relit les blocs suivis d'un chunk chargé, en gardant les scènes de ceux qui sont toujours là. */
    private Tranche lire(World w, int x, int z, Tranche ancienne) {
        Tranche tr = new Tranche();
        tr.lue = moteur.tick();
        Set<Material> s = suivis;
        Collection<BlockState> etats = w.getChunkAt(x, z).getTileEntities(b -> s.contains(b.getType()), false);
        for (BlockState st : etats) {
            long c = cle(st.getX(), st.getY(), st.getZ());
            Genre g = genre(st.getType());
            Entree e = ancienne == null ? null : ancienne.entrees.remove(c);
            if (e == null || e.genre != g) {
                if (e != null && e.scene != null) {
                    e.scene.fermer();
                }
                e = new Entree(w, st.getX(), st.getY(), st.getZ(), g);
            }
            tr.entrees.put(c, e);
        }
        if (ancienne != null) {
            for (Entree e : new ArrayList<>(ancienne.entrees.values())) {
                if (e.scene != null) {
                    e.scene.fermer();
                }
            }
        }
        return tr;
    }

    private void creer(Entree e, Reglages reg, long t, Vue pour) {
        Block b = e.monde.getBlockAt(e.x, e.y, e.z);
        SceneBloc s = switch (e.genre) {
            case SPAWNER -> b.getType() == Material.SPAWNER ? new SceneSpawner(e, reg) : null;
            case BANNIERE -> banniere(e, b, reg);
            case JUKEBOX -> b.getType() == Material.JUKEBOX ? new SceneJukebox(e, reg) : null;
            case CRAFTER -> crafter(e, b, reg);
        };
        if (s == null) {
            e.retenter = t + reg.relecture();
            return;
        }
        e.scene = s;
        s.prochaine = t + 1 + Math.floorMod(e.x * 31 + e.z * 17 + e.y, s.periode(reg));
        s.rang = actives.size();
        actives.add(s);
        moteur.inscrire(s, pour);
        if (s.spectateurs.isEmpty()) {
            s.fermer();
            e.retenter = t + 40;
        }
    }

    private void tic() {
        long t = moteur.tick();
        Reglages reg = plugin.reglages();
        if (t % 1200 == 0) {
            menage(t, reg);
        }
        if (actives.isEmpty()) {
            return;
        }
        for (int i = actives.size() - 1; i >= 0; i--) {
            if (i >= actives.size()) {
                continue;
            }
            SceneBloc s = actives.get(i);
            if (t >= s.prochaine) {
                s.prochaine = t + s.periode(reg);
                s.relire(reg, t);
            }
        }
    }

    /** Oublie les chunks que plus aucun joueur n'approche. */
    private void menage(long t, Reglages reg) {
        for (Map<Long, Tranche> m : mondes.values()) {
            Iterator<Tranche> it = m.values().iterator();
            while (it.hasNext()) {
                Tranche tr = it.next();
                if (t - tr.lue > reg.relecture() * 2L && tr.entrees.values().stream().noneMatch(e -> e.scene != null)) {
                    it.remove();
                }
            }
        }
    }

    private Entree entree(Block b) {
        Map<Long, Tranche> m = mondes.get(b.getWorld().getUID());
        Tranche tr = m == null ? null : m.get(Chunk.getChunkKey(b.getX() >> 4, b.getZ() >> 4));
        return tr == null ? null : tr.entrees.get(cle(b.getX(), b.getY(), b.getZ()));
    }

    /** Le bloc vient de changer sous la main d'un joueur : sa scène est relue au prochain tick. */
    private void bientot(Block b) {
        Entree e = entree(b);
        if (e != null && e.scene != null) {
            e.scene.prochaine = moteur.tick() + 1;
        }
    }

    private void pose(Block b, Material type) {
        Map<Long, Tranche> m = mondes.get(b.getWorld().getUID());
        Tranche tr = m == null ? null : m.get(Chunk.getChunkKey(b.getX() >> 4, b.getZ() >> 4));
        if (tr == null || b.getType() != type) {
            return;
        }
        long c = cle(b.getX(), b.getY(), b.getZ());
        Entree vieille = tr.entrees.put(c, new Entree(b.getWorld(), b.getX(), b.getY(), b.getZ(), genre(type)));
        if (vieille != null && vieille.scene != null) {
            vieille.scene.fermer();
        }
        reveiller(b);
    }

    private void casse(Block b) {
        Map<Long, Tranche> m = mondes.get(b.getWorld().getUID());
        Tranche tr = m == null ? null : m.get(Chunk.getChunkKey(b.getX() >> 4, b.getZ() >> 4));
        if (tr == null) {
            return;
        }
        Entree e = tr.entrees.remove(cle(b.getX(), b.getY(), b.getZ()));
        if (e != null && e.scene != null) {
            e.scene.fermer();
        }
    }

    /** Les joueurs proches d'un bloc tout juste posé sont repassés, même immobiles. */
    private void reveiller(Block b) {
        double p = plugin.reglages().porteeBlocs() + 2;
        for (Vue v : moteur.vues()) {
            if (b.getWorld().getUID().equals(v.monde)) {
                double dx = b.getX() - v.px, dy = b.getY() - v.py, dz = b.getZ() - v.pz;
                if (dx * dx + dy * dy + dz * dz <= p * p) {
                    v.aRevoir = true;
                }
            }
        }
    }

    // ------------------------------------------------------------------ événements (fil du monde : on ne fait que noter)

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnload(WorldUnloadEvent e) {
        UUID monde = e.getWorld().getUID();
        moteur.plusTard(() -> {
            for (SceneBloc s : new ArrayList<>(actives)) {
                if (s.monde.getUID().equals(monde)) {
                    s.fermer();
                }
            }
            mondes.remove(monde);
            // ses rectangles en réserve retiendraient le monde entier en mémoire
            reserve.remove(monde);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Material type = e.getBlockPlaced().getType();
        if (suivis.contains(type)) {
            Block b = e.getBlockPlaced();
            moteur.plusTard(() -> pose(b, type));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (suivis.contains(e.getBlock().getType())) {
            Block b = e.getBlock();
            moteur.plusTard(() -> casse(b));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        explosion(e.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(BlockExplodeEvent e) {
        explosion(e.blockList());
    }

    private void explosion(List<Block> blocs) {
        Set<Material> s = suivis;
        for (Block b : blocs) {
            if (s.contains(b.getType())) {
                moteur.plusTard(() -> casse(b));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b != null && e.getAction() == Action.RIGHT_CLICK_BLOCK && b.getType() == Material.JUKEBOX && suivis.contains(Material.JUKEBOX)) {
            moteur.plusTard(() -> bientot(b));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CrafterCraftEvent e) {
        if (suivis.contains(Material.CRAFTER)) {
            Block b = e.getBlock();
            moteur.plusTard(() -> bientot(b));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (e.getView().getTopInventory().getType() == InventoryType.CRAFTER && suivis.contains(Material.CRAFTER)) {
            Block b = e.getWhoClicked() instanceof Player p ? Tables.bloc(e.getView().getTopInventory(), p) : null;
            if (b != null) {
                moteur.plusTard(() -> bientot(b));
            }
        }
    }

    // ------------------------------------------------------------------ scènes

    private abstract class SceneBloc extends Scene {
        final Entree entree;
        long prochaine;
        /** Sa place dans la liste des scènes en cours, pour l'en sortir sans la parcourir. */
        int rang = -1;

        SceneBloc(Entree e, double x, double y, double z, double portee, int famille) {
            super(Blocs.this.moteur, e.monde, x, y, z, portee, famille);
            this.entree = e;
        }

        abstract int periode(Reglages reg);

        /** Relit le bloc et renvoie ce qui a changé. */
        abstract void relire(Reglages reg, long t);

        Block bloc() {
            return monde.getBlockAt(entree.x, entree.y, entree.z);
        }

        /** Le bloc n'est plus là : la scène se ferme et il n'est plus suivi. */
        void disparu() {
            casse(bloc());
        }

        @Override
        void vide() {
            fermer();
        }

        @Override
        void fin() {
            if (entree.scene == this) {
                entree.scene = null;
            }
            // la dernière de la liste prend sa place : aucune recherche, aucun décalage
            int i = rang;
            if (i >= 0 && i < actives.size() && actives.get(i) == this) {
                SceneBloc derniere = actives.remove(actives.size() - 1);
                if (derniere != this) {
                    actives.set(i, derniere);
                    derniere.rang = i;
                }
            }
            rang = -1;
        }
    }

    // --- spawner

    /** Une lecture du spawner sur tant revérifie aussi que le bloc est toujours là. */
    private static final int VERIFICATIONS = 16;
    /** Paires de rectangles gardées en réserve au plus. */
    private static final int RESERVE = 512;

    /** Un rectangle de texte vide fait 0,125 bloc de large et 0,25 de haut pour une taille de 1, décalé de 0,0125 à droite. */
    private static final float LARGEUR = 0.125f, HAUTEUR = 0.25f, DECALAGE = 0.0125f;

    /**
     * Deux rectangles bout à bout : le remplissage qui s'allonge, et le reste de la barre qui raccourcit d'autant. Le
     * serveur dit une fois « plein dans N ticks » et le jeu du joueur fait avancer la barre ; le spawner n'est relu que
     * pour vérifier qu'elle dit vrai. Chaque joueur la voit devant le bloc, de son côté : c'est la position de l'entité
     * qui change d'un joueur à l'autre.
     */
    private final class SceneSpawner extends SceneBloc {
        private final Affichage fond, barre;
        private final Reglages.Barre r;
        private final boolean bedrock;
        private final Map<Vue, Integer> places = new HashMap<>();
        private boolean premier = true, visible;
        private int delaiVu, total = 1;
        private long pleine;
        /** Le spawner lui-même, gardé d'une lecture à l'autre : lire son compte ne coûte alors presque rien. */
        private CreatureSpawner etat;
        private int lectures;
        /** Joueurs arrivés devant la barre en marche, qui attendent la suite de son mouvement. */
        private final List<Vue> arrivants = new ArrayList<>(2);

        SceneSpawner(Entree e, Reglages reg) {
            super(e, e.x + 0.5, e.y + 0.5, e.z + 0.5, reg.spawner().portee(), SPAWNER);
            r = reg.spawner().barre();
            bedrock = reg.spawner().bedrock();
            java.util.ArrayDeque<Affichage[]> libres = reserve.get(monde.getUID());
            Affichage[] paire = libres == null ? null : libres.poll();
            if (paire == null) {
                double y = e.y + r.hauteur();
                paire = new Affichage[] {Affichage.texte(moteur.pont, monde, x, y, z), Affichage.texte(moteur.pont, monde, x, y, z)};
                regler(paire[0].texte(), r.fond());
                regler(paire[1].texte(), r.remplissage());
            }
            fond = paire[0];
            barre = paire[1];
            poser(0, false);
            fond.relancer(0);
            barre.relancer(0);
            // une paire reprise repart de zéro : ce qui a changé sur elle n'est dû à personne
            fond.paquetChangements();
            barre.paquetChangements();
            parties.add(fond);
            parties.add(barre);
        }

        @Override
        void fin() {
            super.fin();
            // tous ceux qui la voyaient ont reçu son retrait : ses deux rectangles peuvent resservir ailleurs
            java.util.ArrayDeque<Affichage[]> libres = reserve.computeIfAbsent(monde.getUID(), k -> new java.util.ArrayDeque<>());
            if (libres.size() < RESERVE) {
                libres.add(new Affichage[] {fond, barre});
            }
        }

        private void regler(TextDisplay t, Color couleur) {
            t.text(Component.text(" "));
            t.setDefaultBackground(false);
            t.setBackgroundColor(couleur);
            t.setBillboard(Display.Billboard.CENTER);
            t.setBrightness(new Display.Brightness(15, 15));
            t.setSeeThrough(r.aTravers());
            t.setShadowed(false);
            t.setTeleportDuration(r.glissement());
        }

        /**
         * Règle les deux rectangles à une fraction : le remplissage calé à gauche, le reste calé à droite. Ils ne se
         * recouvrent jamais : deux rectangles vus à travers les blocs n'ont pas d'ordre sûr l'un devant l'autre.
         */
        private void poser(float fraction, boolean montree) {
            if (!montree) {
                Transformation rien = new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0, 0, 0), new Quaternionf());
                fond.display().setTransformation(rien);
                barre.display().setTransformation(rien);
                return;
            }
            float l = (float) r.largeur(), fy = (float) r.epaisseur() / HAUTEUR;
            float plein = Math.max(0f, Math.min(1f, fraction)) * l, vide = l - plein;
            barre.display().setTransformation(new Transformation(new Vector3f(-l / 2 + (0.5f - DECALAGE / LARGEUR) * plein, 0, 0), new Quaternionf(),
                    new Vector3f(plein / LARGEUR, fy, 1), new Quaternionf()));
            fond.display().setTransformation(new Transformation(new Vector3f(l / 2 - (0.5f + DECALAGE / LARGEUR) * vide, 0, 0), new Quaternionf(),
                    new Vector3f(vide / LARGEUR, fy, 1), new Quaternionf()));
        }

        @Override
        int periode(Reglages reg) {
            return reg.spawner().verification();
        }

        @Override
        boolean accepte(Vue v) {
            return bedrock || !v.bedrock;
        }

        @Override
        void relire(Reglages reg, long t) {
            // de temps en temps, on vérifie que le bloc est toujours un spawner ; s'il a disparu entre deux, son compte
            // ne bouge plus et la barre se cache d'elle-même
            if (etat == null || lectures++ % VERIFICATIONS == 0) {
                Block b = bloc();
                if (b.getType() != Material.SPAWNER || !(b.getState(false) instanceof CreatureSpawner lu)) {
                    disparu();
                    return;
                }
                etat = lu;
                if (places.size() > spectateurs.size()) {
                    places.keySet().retainAll(spectateurs);
                }
            }
            CreatureSpawner sp = etat;
            int d = Math.max(0, sp.getDelay());
            if (premier) {
                // on ne sait pas encore s'il tourne : la barre reste cachée jusqu'à la prochaine lecture
                premier = false;
                delaiVu = d;
                total = Math.max(1, Math.max(d, sp.getMaxSpawnDelay()));
                return;
            }
            int avant = delaiVu;
            delaiVu = d;
            if (d > avant + 2) {
                // le compte est reparti : une apparition vient d'avoir lieu
                total = d;
                lancer(0f, d, t);
            } else if (d < avant || d == 0) {
                if (d == 0 && avant == 0 && visible && pleine <= t) {
                    // prêt, mais rien n'apparaît (pas de place, trop de mobs autour) : la barre est déjà pleine
                    return;
                }
                total = Math.max(total, d);
                if (!visible || Math.abs((pleine - t) - d) > reg.spawner().tolerance()) {
                    lancer(1f - (float) d / total, d, t);
                }
            } else if (visible) {
                // le compte ne bouge plus : aucun joueur assez près, le spawner dort
                visible = false;
                poser(0, false);
                fond.relancer(0);
                barre.relancer(0);
                envoyerTout();
            }
        }

        /** Met la barre à sa fraction du moment, puis, deux ticks plus tard, la laisse se remplir toute seule. */
        private void lancer(float depart, int reste, long t) {
            visible = true;
            pleine = t + reste;
            long cible = pleine;
            poser(depart, true);
            fond.relancer(0);
            barre.relancer(0);
            envoyerTout();
            moteur.apres(2, () -> {
                if (fermee || !visible || pleine != cible) {
                    return;
                }
                int duree = (int) Math.max(1, cible - moteur.tick());
                poser(1f, true);
                fond.relancer(duree);
                barre.relancer(duree);
                envoyerTout();
            });
        }

        /**
         * Un joueur arrive devant une barre qui avance : il la reçoit à sa fraction du moment, puis, deux ticks plus
         * tard, l'ordre de finir de se remplir. Les joueurs qui la voyaient déjà ne reçoivent rien.
         */
        @Override
        void montrer(Vue v) {
            if (!visible) {
                super.montrer(v);
                return;
            }
            long t = moteur.tick();
            poser(fraction(t), true);
            fond.relancer(0);
            barre.relancer(0);
            super.montrer(v);
            // les rectangles retrouvent ce que les autres en savent, sans rien leur envoyer
            poser(1f, true);
            fond.paquetChangements();
            barre.paquetChangements();
            arrivants.add(v);
            if (arrivants.size() == 1) {
                long cible = pleine;
                moteur.apres(2, () -> finir(cible));
            }
        }

        private float fraction(long t) {
            return 1f - (float) Math.max(0, pleine - t) / total;
        }

        /** Envoie aux seuls arrivants la fin du remplissage, depuis la fraction du moment. */
        private void finir(long cible) {
            List<Vue> pour = new ArrayList<>(arrivants);
            arrivants.clear();
            long t = moteur.tick();
            // entre-temps la barre est repartie pour tous, ou s'est cachée : ils ont déjà reçu ce qu'il faut
            if (fermee || !visible || pleine != cible || pleine - t < 1) {
                return;
            }
            int duree = (int) (pleine - t);
            poser(fraction(t), true);
            fond.relancer(0);
            barre.relancer(0);
            fond.paquetChangements();
            barre.paquetChangements();
            poser(1f, true);
            fond.relancer(duree);
            barre.relancer(duree);
            Object f = fond.paquetChangements(), b = barre.paquetChangements();
            if (f == null || b == null) {
                return;
            }
            Object lot = moteur.pont.lot(List.of(f, b));
            for (Vue v : pour) {
                if (spectateurs.contains(v)) {
                    moteur.envoyer(v, lot);
                }
            }
        }

        @Override
        void apparition(Vue v, Affichage a, List<Object> dans) {
            Integer place = places.get(v);
            if (place == null) {
                place = place(v, -1);
                places.put(v, place);
            }
            double[] p = point(place);
            a.apparition(dans, p[0], p[1], p[2]);
        }

        @Override
        void cacher(Vue v) {
            super.cacher(v);
            places.remove(v);
        }

        @Override
        void suivre(Vue v) {
            Integer place = places.get(v);
            if (place == null) {
                return;
            }
            int nouvelle = place(v, place);
            if (nouvelle == place) {
                return;
            }
            places.put(v, nouvelle);
            double[] p = point(nouvelle);
            if (moteur.pont.deplace()) {
                moteur.envoyer(v, moteur.pont.lot(List.of(moteur.pont.deplacement(fond.id, p[0], p[1], p[2]),
                        moteur.pont.deplacement(barre.id, p[0], p[1], p[2]))));
            } else {
                // ce serveur ne sait pas déplacer : la barre réapparaît à sa nouvelle place, sous les mêmes numéros
                List<Object> paquets = new ArrayList<>(4);
                fond.apparition(paquets, p[0], p[1], p[2]);
                barre.apparition(paquets, p[0], p[1], p[2]);
                moteur.envoyer(v, moteur.pont.lot(paquets));
            }
        }

        /**
         * Position autour du bloc la plus proche du joueur. Il garde l'actuelle tant qu'il reste de son côté : de près,
         * un petit écart suffit à la changer ; de loin, où cet écart ne se voit pas, il faut qu'il passe de côté.
         */
        private int place(Vue v, int actuelle) {
            double dx = v.px - x, dz = v.pz - z, d2 = dx * dx + dz * dz;
            if (d2 < 0.09) {
                return Math.max(actuelle, 0);
            }
            Places p = positions;
            if (actuelle >= 0 && actuelle < p.cos.length) {
                double d = Math.sqrt(d2);
                double alignement = (dx * p.cos[actuelle] + dz * p.sin[actuelle]) / d;
                double eloignement = Math.max(0, Math.min(1, (d - r.suivi()) / 8.0));
                if (alignement >= p.proche + (p.loin - p.proche) * eloignement) {
                    return actuelle;
                }
            }
            return Math.floorMod((int) Math.round(Math.atan2(dz, dx) / p.pas), p.cos.length);
        }

        private double[] point(int place) {
            Places p = positions;
            int i = Math.floorMod(place, p.cos.length);
            return new double[] {x + p.cos[i] * r.distance(), entree.y + r.hauteur(), z + p.sin[i] * r.distance()};
        }
    }

    // --- bannière

    private SceneBloc banniere(Entree e, Block b, Reglages reg) {
        if (!Tag.BANNERS.isTagged(b.getType()) || !(b.getState(false) instanceof Banner etat)) {
            return null;
        }
        Component nom = etat.customName();
        if (nom == null) {
            return null;
        }
        String brut = PlainTextComponentSerializer.plainText().serialize(nom);
        if (brut.isBlank()) {
            return null;
        }
        for (Pattern p : reg.banniere().ignorer()) {
            if (p.matcher(brut).matches()) {
                return null;
            }
        }
        boolean surPied = b.getBlockData() instanceof Rotatable;
        return new SceneBanniere(e, reg, nom, surPied ? reg.banniere().hauteur() : reg.banniere().hauteurMurale());
    }

    /** Le nom d'une bannière ne change jamais une fois posée : il est lu une fois, puis on vérifie seulement qu'elle est là. */
    private final class SceneBanniere extends SceneBloc {
        SceneBanniere(Entree e, Reglages reg, Component nom, double hauteur) {
            super(e, e.x + 0.5, e.y + hauteur, e.z + 0.5, reg.banniere().portee(), ETIQUETTE);
            Affichage a = Affichage.texte(moteur.pont, monde, x, y, z);
            Poses.etiquette(a.texte(), reg.banniere().taille(), reg.banniere().aTravers(), reg.etiquettes());
            a.texte().text(nom);
            parties.add(a);
        }

        @Override
        int periode(Reglages reg) {
            return reg.verificationBlocs();
        }

        @Override
        void relire(Reglages reg, long t) {
            if (!Tag.BANNERS.isTagged(bloc().getType())) {
                disparu();
            }
        }
    }

    // --- jukebox

    private final class SceneJukebox extends SceneBloc {
        private Affichage disque, titre;
        private Tournant tournant;
        private ItemStack vu;

        SceneJukebox(Entree e, Reglages reg) {
            super(e, e.x + 0.5, e.y + reg.jukebox().hauteur(), e.z + 0.5, reg.jukebox().portee(), ETIQUETTE);
            lire(reg);
        }

        @Override
        int periode(Reglages reg) {
            return reg.jukebox().verification();
        }

        @Override
        void relire(Reglages reg, long t) {
            if (bloc().getType() != Material.JUKEBOX) {
                disparu();
            } else {
                lire(reg);
            }
        }

        private void lire(Reglages reg) {
            if (!(bloc().getState(false) instanceof Jukebox etat)) {
                return;
            }
            ItemStack d = etat.getRecord();
            if (d != null && d.getType().isAir()) {
                d = null;
            }
            if (d == null ? vu == null : vu != null && d.isSimilar(vu)) {
                return;
            }
            vu = d == null ? null : d.clone();
            if (d == null) {
                if (tournant != null) {
                    tournant.fini = true;
                    tournant = null;
                }
                if (disque != null) {
                    retirer(disque);
                    disque = null;
                }
                if (titre != null) {
                    retirer(titre);
                    titre = null;
                }
                return;
            }
            Reglages.Flottant fl = reg.objets().flottant();
            if (disque == null) {
                disque = Affichage.objet(moteur.pont, monde, x, y, z);
                Poses.flotter(disque.objet(), d, 0, fl.taille());
                ajouter(disque);
                tournant = new Tournant(moteur, this, disque, fl, 0, fl.taille());
            } else {
                disque.objet().setItemStack(d);
                envoyer(disque);
            }
            if (reg.jukebox().titre()) {
                Component texte = titre(d);
                if (titre == null) {
                    titre = Affichage.texte(moteur.pont, monde, x, entree.y + reg.jukebox().hauteurTitre(), z);
                    Poses.etiquette(titre.texte(), reg.jukebox().tailleTitre(), false, reg.etiquettes());
                    titre.texte().text(texte);
                    ajouter(titre);
                } else {
                    titre.texte().text(texte);
                    envoyer(titre);
                }
            }
        }

        /** Le nom donné au disque, sinon son titre, que le jeu du joueur écrit dans sa langue. */
        private Component titre(ItemStack d) {
            Textes textes = plugin.textes();
            String type = d.getType().name();
            ItemMeta meta = d.getItemMeta();
            Component nom;
            if (meta != null && meta.hasCustomName()) {
                nom = meta.customName();
            } else if (type.startsWith("MUSIC_DISC_")) {
                nom = Component.translatable("jukebox_song.minecraft." + type.substring("MUSIC_DISC_".length()).toLowerCase(Locale.ROOT));
            } else {
                nom = Component.translatable(d.getType().translationKey());
            }
            String cle = "jukebox.couleurs." + type;
            String couleur = textes.texte(cle).isEmpty() ? "jukebox.couleurs.defaut" : cle;
            return textes.avec("jukebox.titre", "disque", nom.colorIfAbsent(textes.couleur(couleur)));
        }
    }

    // --- crafter

    private SceneBloc crafter(Entree e, Block b, Reglages reg) {
        if (b.getType() != Material.CRAFTER || !(b.getBlockData() instanceof org.bukkit.block.data.type.Crafter donnees)) {
            return null;
        }
        // « NORTH_UP » : la sortie au nord, la grille sur le dessus. Couché ou retourné, la grille n'est pas en haut.
        String[] sens = donnees.getOrientation().name().split("_");
        if (sens.length != 2 || !sens[1].equals("UP")) {
            return null;
        }
        BlockFace sortie = BlockFace.valueOf(sens[0]);
        // un crafter se pose face au joueur : la grille se lit depuis sa sortie, le haut de l'interface vers l'arrière
        return new SceneCrafter(e, reg, -sortie.getModX(), -sortie.getModZ());
    }

    private final class SceneCrafter extends SceneBloc {
        private final Affichage[] cases = new Affichage[9];
        private final ItemStack[] vus = new ItemStack[9];
        private final int fx, fz;
        private final float angle;

        SceneCrafter(Entree e, Reglages reg, int fx, int fz) {
            super(e, e.x + 0.5, e.y + reg.crafter().hauteur(), e.z + 0.5, reg.crafter().portee(), CRAFTER);
            this.fx = fx;
            this.fz = fz;
            this.angle = Poses.angle(fx, fz);
            lire(reg);
        }

        @Override
        int periode(Reglages reg) {
            return reg.crafter().verification();
        }

        @Override
        void relire(Reglages reg, long t) {
            if (bloc().getType() != Material.CRAFTER) {
                disparu();
            } else {
                lire(reg);
            }
        }

        private void lire(Reglages reg) {
            if (!(bloc().getState(false) instanceof org.bukkit.block.Crafter etat)) {
                return;
            }
            Inventory inv = etat.getInventory();
            Reglages.Crafter c = reg.crafter();
            for (int i = 0; i < 9; i++) {
                ItemStack o = etat.isSlotDisabled(i) ? new ItemStack(c.caseFermee()) : inv.getItem(i);
                if (o != null && o.getType().isAir()) {
                    o = null;
                }
                if (o == null ? vus[i] == null : vus[i] != null && o.isSimilar(vus[i])) {
                    continue;
                }
                vus[i] = o == null ? null : o.clone();
                Affichage a = cases[i];
                if (o == null) {
                    retirer(a);
                    cases[i] = null;
                    continue;
                }
                boolean plat = !o.getType().isBlock() || reg.objets().aPlat().contains(o.getType());
                if (a == null) {
                    double droite = (i % 3 - 1) * c.ecart(), fond = (1 - i / 3) * c.ecart();
                    a = Affichage.objet(moteur.pont, monde, x - fz * droite + fx * fond, y, z + fx * droite + fz * fond);
                    Poses.poser(a.objet(), o, plat, angle, reg.objets());
                    cases[i] = a;
                    ajouter(a);
                } else {
                    Poses.poser(a.objet(), o, plat, angle, reg.objets());
                    envoyer(a);
                }
            }
        }
    }
}
