package org.Cry_Legende.sHVisualizer;

import com.destroystokyo.paper.event.player.PlayerRecipeBookClickEvent;
import io.papermc.paper.event.player.PlayerLoomPatternSelectEvent;
import io.papermc.paper.event.player.PlayerStonecutterRecipeSelectEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Rotation;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.util.Transformation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Les blocs dont l'interface s'ouvre : tant qu'un joueur s'en sert, ce qu'il met dans les cases est montré sur le bloc.
 * Une table n'est relue que lorsqu'un clic l'a changée (et de loin en loin par sécurité), et seules les cases qui ont
 * changé sont renvoyées.
 */
final class Tables implements Listener {

    private final SHVisualizer plugin;
    private final Moteur moteur;
    private final Effets effets;
    /** Lue depuis les événements (fil du monde), d'où la table concurrente ; l'état d'une session ne change que sur le fil principal. */
    private final Map<UUID, Session> parJoueur = new ConcurrentHashMap<>();
    private final Map<Block, Session> parBloc = new HashMap<>();
    private boolean caseFausse;

    private final class Session {
        final Player joueur;
        final InventoryView vue;
        final Block bloc;
        final Reglages.Table def;
        final SceneTable scene;
        final double cx, by, cz;
        final int fx, fz;
        final float angle;
        final Affichage[] poses;
        final ItemStack[] vus;
        /** Résultat flottant d'une table « pose », ou l'unique objet des autres modes. */
        Affichage resultat;
        ItemStack vuResultat;
        Tournant tournant;
        Affichage nom;
        Affichage texte;
        /** L'objet unique montré est le résultat (qui n'est pas encore au joueur), pas ce qu'il a posé. */
        boolean previsualisation;
        MapView carte;
        final Set<UUID> carteEnvoyee = new HashSet<>();
        volatile boolean sale = true;
        /** Posés par le clic sur la case du résultat : l'objet visé, et les cases d'entrée juste avant. */
        volatile ItemStack prise;
        volatile ItemStack[] avant;
        long dernierePrise = -1000;
        boolean anim;
        boolean finie;

        Session(Player joueur, InventoryView vue, Block bloc, Reglages.Table def, double portee) {
            this.joueur = joueur;
            this.vue = vue;
            this.bloc = bloc;
            this.def = def;
            this.cx = bloc.getX() + 0.5;
            this.by = bloc.getY();
            this.cz = bloc.getZ() + 0.5;
            int[] f = Poses.devant(joueur, cx, cz);
            this.fx = f[0];
            this.fz = f[1];
            this.angle = Poses.angle(fx, fz);
            this.poses = new Affichage[def.cases().size()];
            this.vus = new ItemStack[def.cases().size()];
            this.scene = new SceneTable(bloc.getWorld(), cx, by + def.hauteur(), cz, portee, this);
        }
    }

    /** Une carte n'est dessinée chez un joueur que s'il en a reçu le contenu : on le lui envoie quand il la découvre. */
    private final class SceneTable extends Scene {
        private final Session session;

        SceneTable(World monde, double x, double y, double z, double portee, Session session) {
            super(Tables.this.moteur, monde, x, y, z, portee, LIBRE);
            this.session = session;
        }

        @Override
        void montrer(Vue v) {
            super.montrer(v);
            envoyerCarte(session, v);
        }
    }

    Tables(SHVisualizer plugin, Moteur moteur, Effets effets) {
        this.plugin = plugin;
        this.moteur = moteur;
        this.effets = effets;
        moteur.chaqueTick("tables", this::tic);
    }

    int ouvertes() {
        return parJoueur.size();
    }

    // ------------------------------------------------------------------ événements

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (plugin.reglages().tables().actif() && e.getPlayer() instanceof Player p) {
            InventoryView v = e.getView();
            moteur.plusTard(() -> ouvrir(p, v));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        Session s = parJoueur.get(e.getWhoClicked().getUniqueId());
        if (s == null || e.getView() != s.vue) {
            return;
        }
        int raw = e.getRawSlot();
        boolean haut = raw >= 0 && raw < e.getView().getTopInventory().getSize();
        if (haut && raw == s.def.caseResultat()) {
            ItemStack vise = e.getCurrentItem();
            InventoryAction a = e.getAction();
            if (vise != null && !vise.getType().isAir() && a != InventoryAction.NOTHING
                    && a != InventoryAction.DROP_ONE_SLOT && a != InventoryAction.DROP_ALL_SLOT) {
                s.avant = entrees(s);
                s.prise = vise.clone();
            }
        }
        s.sale = true;
        if (haut && plugin.reglages().joueurs().geste()) {
            s.joueur.swingMainHand();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        Session s = parJoueur.get(e.getWhoClicked().getUniqueId());
        if (s == null || e.getView() != s.vue) {
            return;
        }
        s.sale = true;
        if (plugin.reglages().joueurs().geste()) {
            int taille = e.getView().getTopInventory().getSize();
            for (int raw : e.getRawSlots()) {
                if (raw < taille) {
                    s.joueur.swingMainHand();
                    break;
                }
            }
        }
    }

    /** Le nom tapé sur l'enclume change le résultat sans aucun clic. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAnvil(PrepareAnvilEvent e) {
        salir(e.getView().getPlayer().getUniqueId());
    }

    /** Une recette choisie dans le livre remplit la grille sans aucun clic sur ses cases. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRecipeBook(PlayerRecipeBookClickEvent e) {
        salir(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStonecutter(PlayerStonecutterRecipeSelectEvent e) {
        salir(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLoom(PlayerLoomPatternSelectEvent e) {
        salir(e.getPlayer().getUniqueId());
    }

    private void salir(UUID id) {
        Session s = parJoueur.get(id);
        if (s != null) {
            s.sale = true;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent e) {
        Session s = parJoueur.get(e.getEnchanter().getUniqueId());
        if (s != null && s.def.mode() == Reglages.Mode.ENCHANTEMENT) {
            Map<Enchantment, Integer> ajouts = new LinkedHashMap<>(e.getEnchantsToAdd());
            int cout = e.getExpLevelCost();
            moteur.plusTard(() -> enchanter(s, ajouts, cout));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        Session s = parJoueur.get(e.getPlayer().getUniqueId());
        if (s != null && e.getView() == s.vue) {
            boolean voulu = e.getReason() == InventoryCloseEvent.Reason.PLAYER;
            moteur.plusTard(() -> terminer(s, voulu));
        }
    }

    // ------------------------------------------------------------------ vie d'une session

    private void ouvrir(Player p, InventoryView v) {
        Reglages reg = plugin.reglages();
        if (!p.isOnline() || p.getOpenInventory() != v) {
            return;
        }
        Session vieille = parJoueur.get(p.getUniqueId());
        if (vieille != null) {
            terminer(vieille, false);
        }
        Block b = bloc(v.getTopInventory(), p);
        if (b == null) {
            return;
        }
        Reglages.Table def = reg.tables().parBloc().get(b.getType());
        if (def == null || parBloc.containsKey(b) || reg.joueurs().mondesIgnores().contains(b.getWorld().getName()) || effets.discret(p)) {
            return;
        }
        Session s = new Session(p, v, b, def, reg.tables().portee());
        parJoueur.put(p.getUniqueId(), s);
        parBloc.put(b, s);
        moteur.inscrire(s.scene);
        rafraichir(s);
    }

    /** Le bloc dont l'interface est ouverte ; null pour un menu qui n'en est pas un. */
    /** Au-delà, une interface n'est plus celle d'un bloc que le joueur a devant lui. */
    private static final double PROCHE = 16;

    /**
     * Le bloc dont cette interface est celle, s'il est bien là où se tient le joueur. null pour tout le reste :
     * l'inventaire d'un autre joueur regardé par un membre du staff, un menu de plugin, un bloc ouvert de loin ou dans un
     * autre monde. Rien n'est lu dans le monde tant que ce n'est pas sûr.
     */
    static Block bloc(Inventory haut, Player p) {
        if (haut.getType() == InventoryType.PLAYER) {
            return null;
        }
        try {
            Location l = haut.getLocation();
            if (l == null || l.getWorld() == null || l.getWorld() != p.getWorld()) {
                return null;
            }
            Location ou = p.getLocation();
            double dx = l.getX() - ou.getX(), dy = l.getY() - ou.getY(), dz = l.getZ() - ou.getZ();
            return dx * dx + dy * dy + dz * dz > PROCHE * PROCHE ? null : l.getBlock();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void tic() {
        if (parJoueur.isEmpty()) {
            return;
        }
        boolean filet = moteur.tick() % plugin.reglages().tables().verification() == 0;
        for (Session s : parJoueur.values()) {
            if (s.sale || filet) {
                s.sale = false;
                try {
                    rafraichir(s);
                } catch (RuntimeException e) {
                    // cette table s'arrête, les autres continuent
                    terminer(s, false);
                    moteur.signaler(e);
                }
            }
        }
    }

    private void rafraichir(Session s) {
        if (s.finie) {
            return;
        }
        if (!s.joueur.isOnline() || s.joueur.getOpenInventory() != s.vue) {
            terminer(s, false);
            return;
        }
        Reglages reg = plugin.reglages();
        switch (s.def.mode()) {
            case POSE -> pose(s, reg);
            case UNIQUE -> unique(s, reg);
            case ENCHANTEMENT -> {
                if (!s.anim) {
                    ItemStack objet = lire(s, s.def.cases().get(0).numero());
                    if (!memes(objet, s.vuResultat)) {
                        s.vuResultat = objet;
                        majUnique(s, objet, reg);
                    }
                }
            }
        }
    }

    private ItemStack lire(Session s, int raw) {
        try {
            ItemStack o = s.vue.getItem(raw);
            return o == null || o.getType().isAir() ? null : o.clone();
        } catch (RuntimeException e) {
            if (!caseFausse) {
                caseFausse = true;
                plugin.getLogger().warning("config.yml, tables.blocs." + s.def.id() + " : la case " + raw + " n'existe pas dans cette interface");
            }
            return null;
        }
    }

    private ItemStack[] entrees(Session s) {
        List<Reglages.Case> cases = s.def.cases();
        ItemStack[] out = new ItemStack[cases.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = lire(s, cases.get(i).numero());
        }
        return out;
    }

    /** Même objet, quelle que soit la quantité : c'est tout ce que l'affichage montre. */
    private static boolean memes(ItemStack a, ItemStack b) {
        return a == null ? b == null : b != null && a.isSimilar(b);
    }

    /** Les cases d'entrée ont-elles changé depuis « avant » (objet ou quantité) : le résultat a bien été pris. */
    private boolean change(Session s, ItemStack[] avant) {
        ItemStack[] maintenant = entrees(s);
        for (int i = 0; i < avant.length; i++) {
            ItemStack a = avant[i], b = maintenant[i];
            if (a == null ? b != null : b == null || !a.isSimilar(b) || a.getAmount() != b.getAmount()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ mode « pose »

    private void pose(Session s, Reglages reg) {
        ItemStack prise = s.prise;
        if (prise != null) {
            ItemStack[] avant = s.avant;
            s.prise = null;
            s.avant = null;
            if (avant != null && change(s, avant)) {
                prendre(s, prise, reg);
            }
        }
        List<Reglages.Case> cases = s.def.cases();
        for (int i = 0; i < cases.size(); i++) {
            ItemStack objet = lire(s, cases.get(i).numero());
            if (!memes(objet, s.vus[i])) {
                s.vus[i] = objet;
                majPose(s, i, cases.get(i), objet, reg);
            }
        }
        ItemStack resultat = lire(s, s.def.caseResultat());
        if (!memes(resultat, s.vuResultat)) {
            s.vuResultat = resultat;
            majResultat(s, resultat, reg);
        }
    }

    private void majPose(Session s, int i, Reglages.Case c, ItemStack objet, Reglages reg) {
        Affichage a = s.poses[i];
        if (objet == null) {
            if (a != null) {
                s.scene.retirer(a);
                s.poses[i] = null;
            }
            return;
        }
        float angle = s.angle + (float) Math.toRadians(c.tourne());
        if (a == null) {
            // à droite du joueur : (-fz, fx) ; au fond : (fx, fz)
            double x = s.cx - s.fz * c.droite() + s.fx * c.fond();
            double z = s.cz + s.fx * c.droite() + s.fz * c.fond();
            a = Affichage.objet(moteur.pont, s.scene.monde, x, s.by + s.def.hauteur(), z);
            Poses.poser(a.objet(), objet, effets.aPlat(objet), angle, reg.objets());
            s.poses[i] = a;
            s.scene.ajouter(a);
        } else {
            Poses.poser(a.objet(), objet, effets.aPlat(objet), angle, reg.objets());
            s.scene.envoyer(a);
        }
    }

    private void majResultat(Session s, ItemStack objet, Reglages reg) {
        if (objet == null) {
            oterResultat(s);
            return;
        }
        Reglages.Flottant fl = reg.objets().flottant();
        if (s.resultat == null) {
            Affichage a = Affichage.objet(moteur.pont, s.scene.monde, s.cx, s.by + s.def.hauteurResultat(), s.cz);
            Poses.flotter(a.objet(), objet, s.angle, fl.taille());
            s.resultat = a;
            s.scene.ajouter(a);
            s.tournant = new Tournant(moteur, s.scene, a, fl, s.angle, fl.taille());
        } else {
            s.resultat.objet().setItemStack(objet);
            s.scene.envoyer(s.resultat);
        }
        if (s.def.nomResultat()) {
            majNom(s, objet, reg);
        }
    }

    private void oterResultat(Session s) {
        if (s.tournant != null) {
            s.tournant.fini = true;
            s.tournant = null;
        }
        if (s.resultat != null) {
            s.scene.retirer(s.resultat);
            s.resultat = null;
        }
        if (s.nom != null) {
            s.scene.retirer(s.nom);
            s.nom = null;
        }
    }

    /** Le nom donné à l'objet (sur l'enclume, celui qu'on est en train de taper), écrit au-dessus de lui. */
    private void majNom(Session s, ItemStack objet, Reglages reg) {
        Reglages.Flottant fl = reg.objets().flottant();
        ItemMeta meta = objet.getItemMeta();
        Component nom = meta != null && meta.hasCustomName() ? meta.customName() : null;
        if (nom == null) {
            if (s.nom != null) {
                s.scene.retirer(s.nom);
                s.nom = null;
            }
        } else if (s.nom == null) {
            Affichage a = Affichage.texte(moteur.pont, s.scene.monde, s.cx, s.by + s.def.hauteurResultat() + 0.43 * fl.taille(), s.cz);
            Poses.etiquette(a.texte(), reg.tables().tailleNom(), false, reg.etiquettes());
            a.texte().text(nom);
            s.nom = a;
            s.scene.ajouter(a);
        } else {
            s.nom.texte().text(nom);
            s.scene.envoyer(s.nom);
        }
    }

    /**
     * Le résultat vient d'être pris : les objets posés quittent la session pour une scène courte où ils se rejoignent au
     * centre, puis le résultat vole vers le joueur. La session repart de zéro et montre aussitôt ce qui reste.
     */
    private void prendre(Session s, ItemStack prise, Reglages reg) {
        Reglages.Prise p = reg.tables().prise();
        long t = moteur.tick();
        if (t - s.dernierePrise < p.pause() || s.scene.spectateurs.isEmpty()) {
            return;
        }
        s.dernierePrise = t;
        World monde = s.scene.monde;
        double hauteur = s.by + s.def.hauteur();
        Scene effet = new Scene(moteur, monde, s.cx, hauteur, s.cz, s.scene.portee, Scene.LIBRE);
        List<Affichage> poses = new ArrayList<>();
        for (int i = 0; i < s.poses.length; i++) {
            if (s.poses[i] != null) {
                poses.add(s.poses[i]);
                s.scene.parties.remove(s.poses[i]);
                effet.parties.add(s.poses[i]);
            }
        }
        Affichage resultat = s.resultat;
        float angle = s.tournant != null ? s.tournant.angle : s.angle;
        if (s.tournant != null) {
            s.tournant.fini = true;
            s.tournant = null;
        }
        if (resultat != null) {
            s.scene.parties.remove(resultat);
            effet.parties.add(resultat);
            s.resultat = null;
        }
        if (s.nom != null) {
            s.scene.retirer(s.nom);
            s.nom = null;
        }
        Arrays.fill(s.poses, null);
        Arrays.fill(s.vus, null);
        s.vuResultat = null;
        moteur.heriter(effet, s.scene);

        for (Affichage a : poses) {
            Transformation de = a.display().getTransformation();
            a.display().setTransformation(Poses.vers(de, (float) (s.cx - a.x), de.getTranslation().y, (float) (s.cz - a.z), p.tailleFinale()));
            a.relancer(p.rapprochement());
            effet.envoyer(a);
        }
        Player joueur = s.joueur;
        double rx = s.cx, ry = s.by + s.def.hauteurResultat(), rz = s.cz;
        float taille = reg.objets().flottant().taille();
        moteur.apres(p.rapprochement(), () -> {
            if (effet.fermee) {
                return;
            }
            try {
                effet.retirer(poses);
                effets.particules(effet, p.particule(), rx, hauteur + 0.12, rz, p.quantite(), p.etalement(), p.vitesse(), null);
                if (resultat != null) {
                    resultat.objet().setItemStack(prise);
                    effets.envol(effet, resultat, angle, joueur, true);
                } else {
                    Affichage a = Affichage.objet(moteur.pont, monde, rx, ry, rz);
                    Poses.flotter(a.objet(), prise, angle, taille);
                    effet.ajouter(a);
                    moteur.apres(2, () -> effets.envol(effet, a, angle, joueur, true));
                }
            } catch (RuntimeException e) {
                // personne d'autre ne suit cette scène : elle ne doit pas rester chez les joueurs
                effet.fermer();
                throw e;
            }
        });
    }

    // ------------------------------------------------------------------ mode « unique »

    private void unique(Session s, Reglages reg) {
        ItemStack resultat = lire(s, s.def.caseResultat());
        ItemStack montre = resultat;
        if (montre == null) {
            for (Reglages.Case c : s.def.cases()) {
                montre = lire(s, c.numero());
                if (montre != null) {
                    break;
                }
            }
        }
        ItemStack prise = s.prise;
        if (prise != null) {
            ItemStack[] avant = s.avant;
            s.prise = null;
            s.avant = null;
            long t = moteur.tick();
            if (avant != null && change(s, avant) && t - s.dernierePrise >= reg.tables().prise().pause()) {
                s.dernierePrise = t;
                double[] c = effets.cible(s.joueur);
                double y = s.by + s.def.hauteur() + 0.2;
                effets.vol(s.scene.monde, prise, s.cx, y, s.cz, c[0], c[1], c[2], s.cx, y, s.cz, s.scene.portee, true);
            }
        }
        s.previsualisation = resultat != null;
        if (!memes(montre, s.vuResultat)) {
            s.vuResultat = montre;
            majUnique(s, montre, reg);
            if (montre != null && s.def.eclats() > 0) {
                effets.particules(s.scene, Particle.ITEM, s.cx, s.by + s.def.hauteur() + 0.15, s.cz, s.def.eclats(), 0.08, 0.06, montre);
            }
        }
    }

    private void majUnique(Session s, ItemStack objet, Reglages reg) {
        boolean carte = objet != null && s.def.presentation() == Reglages.Presentation.CARTE && objet.getType() == Material.FILLED_MAP;
        if (objet == null || (s.resultat != null && s.resultat.estCadre() != carte)) {
            oterResultat(s);
            s.carte = null;
            if (objet == null) {
                return;
            }
        }
        World monde = s.scene.monde;
        double y = s.by + s.def.hauteur();
        Reglages.Flottant fl = reg.objets().flottant();
        boolean neuf = s.resultat == null;
        if (carte) {
            if (neuf) {
                try {
                    // un cadre tient à 1/32 de bloc de la face qui le porte
                    s.resultat = Affichage.cadre(moteur.pont, monde, s.cx, y + 0.03125, s.cz);
                } catch (RuntimeException e) {
                    carte = false;
                }
            }
            if (carte) {
                s.resultat.cadre().setItem(objet, false);
                s.resultat.cadre().setRotation(sens(s));
                MapView vue = objet.getItemMeta() instanceof MapMeta m && m.hasMapView() ? m.getMapView() : null;
                if (vue != s.carte) {
                    s.carte = vue;
                    s.carteEnvoyee.clear();
                    for (Vue v : s.scene.spectateurs) {
                        envoyerCarte(s, v);
                    }
                }
            }
        }
        if (!carte) {
            if (neuf) {
                s.resultat = Affichage.objet(moteur.pont, monde, s.cx, y, s.cz);
            }
            switch (s.def.presentation()) {
                case DEBOUT -> Poses.dresser(s.resultat.objet(), objet, s.angle, s.def.taille());
                case CARTE -> Poses.poser(s.resultat.objet(), objet, true, s.angle, reg.objets());
                default -> {
                    if (neuf) {
                        Poses.flotter(s.resultat.objet(), objet, s.angle, s.def.taille() * fl.taille());
                    } else {
                        s.resultat.objet().setItemStack(objet);
                    }
                }
            }
        }
        if (neuf) {
            s.scene.ajouter(s.resultat);
            if (!carte && s.def.presentation() == Reglages.Presentation.FLOTTANT && s.def.tourne()) {
                s.tournant = new Tournant(moteur, s.scene, s.resultat, fl, s.angle, s.def.taille() * fl.taille());
            }
        } else {
            s.scene.envoyer(s.resultat);
        }
    }

    /** Sens de la carte dans son cadre, pour que son haut soit loin du joueur. */
    private static Rotation sens(Session s) {
        if (s.fz < 0) {
            return Rotation.NONE;
        }
        if (s.fx > 0) {
            return Rotation.CLOCKWISE_45;
        }
        return s.fz > 0 ? Rotation.CLOCKWISE : Rotation.CLOCKWISE_135;
    }

    private void envoyerCarte(Session s, Vue v) {
        if (s.carte != null && s.carteEnvoyee.add(v.joueur.getUniqueId())) {
            v.joueur.sendMap(s.carte);
        }
    }

    // ------------------------------------------------------------------ table d'enchantement

    /** L'objet monte, ses enchantements s'écrivent au-dessus, puis il redescend. */
    private void enchanter(Session s, Map<Enchantment, Integer> ajouts, int cout) {
        Reglages reg = plugin.reglages();
        if (s.finie || s.anim) {
            return;
        }
        ItemStack objet = lire(s, s.def.cases().get(0).numero());
        if (objet == null) {
            return;
        }
        s.vuResultat = objet;
        if (s.resultat == null) {
            majUnique(s, objet, reg);
        } else {
            s.resultat.objet().setItemStack(objet);
        }
        Reglages.Enchantement e = reg.tables().enchantement();
        Affichage a = s.resultat;
        Tournant t = s.tournant;
        float taille = s.def.taille() * reg.objets().flottant().taille();
        float montee = (float) e.montee();
        s.anim = true;
        if (t != null) {
            t.pause = true;
        }
        a.display().setTransformation(Poses.flottant(0, montee, 0, t != null ? t.angle : s.angle, taille));
        a.relancer(e.dureeMontee());
        s.scene.envoyer(a);
        double y = s.by + s.def.hauteur();
        effets.particules(s.scene, e.particule(), s.cx, y + montee + 0.25, s.cz, e.quantite(), 0, e.vitesse(), null);

        moteur.apres(e.dureeMontee(), () -> {
            if (s.finie) {
                return;
            }
            if (t != null) {
                t.hauteur = montee;
                t.pause = false;
            }
            Affichage texte = Affichage.texte(moteur.pont, s.scene.monde, s.cx, y + montee + e.hauteurTexte(), s.cz);
            Poses.etiquette(texte.texte(), e.tailleTexte(), false, reg.etiquettes());
            texte.texte().text(lignes(ajouts, cout));
            s.texte = texte;
            s.scene.ajouter(texte);
        });
        moteur.apres(e.dureeMontee() + e.maintien(), () -> {
            if (s.finie) {
                return;
            }
            if (s.texte != null) {
                s.scene.retirer(s.texte);
                s.texte = null;
            }
            if (t != null) {
                t.pause = true;
            }
            a.display().setTransformation(Poses.flottant(0, 0, 0, t != null ? t.angle : s.angle, taille));
            a.relancer(e.dureeDescente());
            s.scene.envoyer(a);
        });
        moteur.apres(e.dureeMontee() + e.maintien() + e.dureeDescente(), () -> {
            if (s.finie) {
                return;
            }
            if (t != null) {
                t.hauteur = 0;
                t.pause = false;
            }
            s.anim = false;
            s.sale = true;
        });
    }

    /** Une ligne par enchantement, du plus haut niveau au plus bas, puis le prix. Les noms sont traduits par le jeu du joueur. */
    private Component lignes(Map<Enchantment, Integer> ajouts, int cout) {
        Textes textes = plugin.textes();
        TextColor normal = textes.couleur("enchantement.couleur"), maudit = textes.couleur("enchantement.malediction");
        List<Map.Entry<Enchantment, Integer>> tri = new ArrayList<>(ajouts.entrySet());
        tri.sort((x, y) -> Integer.compare(y.getValue(), x.getValue()));
        Component out = Component.empty();
        for (Map.Entry<Enchantment, Integer> en : tri) {
            out = out.append(en.getKey().displayName(en.getValue()).color(en.getKey().isCursed() ? maudit : normal)).append(Component.newline());
        }
        String prix = cout == 1 ? textes.texte("enchantement.cout-un") : textes.texte("enchantement.cout", "n", Integer.toString(cout));
        return out.append(Textes.composant(prix));
    }

    // ------------------------------------------------------------------ fermeture

    /**
     * @param retour le joueur a fermé lui-même : ce qu'il avait posé retourne vers lui
     */
    private void terminer(Session s, boolean retour) {
        if (s.finie) {
            return;
        }
        s.finie = true;
        parJoueur.remove(s.joueur.getUniqueId(), s);
        parBloc.remove(s.bloc, s);
        if (s.tournant != null) {
            s.tournant.fini = true;
        }
        Reglages reg = plugin.reglages();
        List<Affichage> rentrent = new ArrayList<>();
        if (retour && reg.tables().retour() && s.joueur.isOnline() && s.joueur.getWorld() == s.scene.monde && !s.scene.spectateurs.isEmpty()) {
            if (s.def.mode() == Reglages.Mode.POSE) {
                for (Affichage a : s.poses) {
                    if (a != null) {
                        rentrent.add(a);
                    }
                }
            } else if (s.resultat != null && !s.resultat.estCadre() && !s.previsualisation) {
                rentrent.add(s.resultat);
            }
        }
        if (rentrent.isEmpty()) {
            s.scene.fermer();
            return;
        }
        Scene effet = new Scene(moteur, s.scene.monde, s.cx, s.by + s.def.hauteur(), s.cz, s.scene.portee, Scene.LIBRE);
        for (Affichage a : rentrent) {
            s.scene.parties.remove(a);
            effet.parties.add(a);
        }
        moteur.heriter(effet, s.scene);
        s.scene.fermer();
        double[] c = effets.cible(s.joueur);
        int duree = reg.objets().vol().duree();
        for (Affichage a : rentrent) {
            Transformation de = a.display().getTransformation();
            a.display().setTransformation(Poses.vers(de, (float) (c[0] - a.x), (float) (c[1] - a.y), (float) (c[2] - a.z), reg.tables().prise().tailleFinale()));
            a.relancer(duree);
            effet.envoyer(a);
        }
        moteur.apres(duree, effet::fermer);
    }

    void fermerTout() {
        for (Session s : new ArrayList<>(parJoueur.values())) {
            terminer(s, false);
        }
    }
}
