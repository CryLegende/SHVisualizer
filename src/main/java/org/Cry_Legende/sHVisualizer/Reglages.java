package org.Cry_Legende.sHVisualizer;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * config.yml lu et vérifié une fois. Une valeur absente du fichier du serveur prend celle du fichier du jar ; une
 * valeur fausse arrête la lecture avec le nom de la clé, et l'ancienne configuration reste en place.
 */
record Reglages(Joueurs joueurs, Objets objets, Tables tables, Conteneurs conteneurs, Crafter crafter, Spawner spawner,
                Banniere banniere, Jukebox jukebox, Notes notes, Etiquettes etiquettes, int relecture,
                int verificationBlocs, int rayonChunks) {

    record Etiquettes(int maximum, Color fond, boolean ombre, Display.Billboard orientation) {
    }

    private static Display.Billboard orientation(Lecteur l) {
        String o = l.texte("etiquettes.orientation").trim().toLowerCase(Locale.ROOT);
        return switch (o) {
            case "debout" -> Display.Billboard.VERTICAL;
            case "face" -> Display.Billboard.CENTER;
            default -> throw new IllegalArgumentException("« etiquettes.orientation » : « debout » ou « face », pas « " + o + " »");
        };
    }

    record Joueurs(boolean actifParDefaut, int periode, boolean geste, Set<String> mondesIgnores) {
    }

    record Objets(Set<Material> aPlat, float taillePlat, float tailleVolume, Flottant flottant, Vol vol) {
    }

    record Flottant(float taille, float rotation, int pas) {
    }

    record Vol(int duree, float taille, float tailleJoueur, float tour, double hauteurJoueur, String son, float volume,
               float tonMin, float tonMax) {
    }

    record Tables(boolean actif, double portee, int verification, Prise prise, boolean retour, float tailleNom,
                  Enchantement enchantement, Map<Material, Table> parBloc) {
    }

    record Prise(int rapprochement, float tailleFinale, Particle particule, int quantite, double etalement, double vitesse, int pause) {
    }

    record Enchantement(double montee, int dureeMontee, int maintien, int dureeDescente, Particle particule, int quantite,
                        double vitesse, double hauteurTexte, float tailleTexte) {
    }

    enum Mode {POSE, UNIQUE, ENCHANTEMENT}

    enum Presentation {DEBOUT, FLOTTANT, CARTE}

    record Case(int numero, double droite, double fond, float tourne) {
    }

    record Table(String id, Mode mode, Presentation presentation, double hauteur, float taille, boolean tourne, int eclats,
                 List<Case> cases, int caseResultat, double hauteurResultat, boolean nomResultat) {
    }

    record Conteneurs(boolean actif, double portee, int pause, boolean menus, Map<Material, Conteneur> parBloc) {
    }

    record Conteneur(String id, boolean dessus, double hauteur, double distance) {
    }

    record Crafter(boolean actif, double portee, int maximum, double hauteur, double ecart, Material caseFermee, int verification) {
    }

    record Spawner(boolean actif, double portee, int maximum, int verification, int tolerance, Barre barre, boolean bedrock) {
    }

    record Barre(double largeur, double epaisseur, double distance, double hauteur, Color fond, Color remplissage,
                 boolean aTravers, int positions, int glissement, double suivi) {
    }

    record Banniere(boolean actif, double portee, double hauteur, double hauteurMurale, float taille, boolean aTravers,
                    List<Pattern> ignorer) {
    }

    record Jukebox(boolean actif, double portee, double hauteur, boolean titre, double hauteurTitre, float tailleTitre,
                   int verification) {
    }

    record Notes(boolean actif, double portee, int duree, double distance, float taille) {
    }

    int plafond(int famille) {
        return switch (famille) {
            case Scene.SPAWNER -> spawner.maximum();
            case Scene.CRAFTER -> crafter.maximum();
            default -> etiquettes.maximum();
        };
    }

    /** Plus grande portée des scènes faites à la demande autour d'un joueur (spawner, bannière, jukebox, crafter). */
    double porteeBlocs() {
        double p = 0;
        if (spawner.actif()) {
            p = Math.max(p, spawner.portee());
        }
        if (banniere.actif()) {
            p = Math.max(p, banniere.portee());
        }
        if (jukebox.actif()) {
            p = Math.max(p, jukebox.portee());
        }
        if (crafter.actif()) {
            p = Math.max(p, crafter.portee());
        }
        return p;
    }

    // ------------------------------------------------------------------ lecture

    /**
     * @param fichier  config.yml du serveur
     * @param defauts  config.yml du jar
     * @param avertir  reçoit ce qui mérite une ligne dans la console sans empêcher de charger
     */
    static Reglages lire(YamlConfiguration fichier, YamlConfiguration defauts, Consumer<String> avertir) {
        Lecteur l = new Lecteur(fichier, defauts, avertir);

        Joueurs joueurs = new Joueurs(l.bool("joueurs.actif-par-defaut"), l.entier("joueurs.periode", 1, 200),
                l.bool("joueurs.geste-du-bras"), new HashSet<>(l.textes("joueurs.mondes-ignores")));

        Flottant flottant = new Flottant(l.reel("objets.flottant.taille", 0.1, 4), l.reel("objets.flottant.rotation", 0, 8.5f),
                l.entier("objets.flottant.pas", 2, 100));
        if (flottant.rotation() * flottant.pas() >= 175f) {
            throw new IllegalArgumentException("objets.flottant : rotation × pas doit rester sous 175 degrés (le jeu tourne par le plus court)");
        }
        Vol vol = new Vol(l.entier("objets.vol.duree", 1, 60), l.reel("objets.vol.taille", 0.05, 4),
                l.reel("objets.vol.taille-joueur", 0.01, 4), l.reel("objets.vol.tour", 0, 175), l.reel("objets.vol.hauteur-joueur", 0, 3),
                l.son("objets.vol.son"), l.reel("objets.vol.volume", 0, 4), l.reel("objets.vol.ton-min", 0.5, 2), l.reel("objets.vol.ton-max", 0.5, 2));
        Objets objets = new Objets(l.materiaux("objets.a-plat", l.textes("objets.a-plat")), l.reel("objets.pose.taille-plat", 0.02, 1),
                l.reel("objets.pose.taille-volume", 0.02, 1), flottant, vol);

        Prise prise = new Prise(l.entier("tables.prise.rapprochement", 1, 60), l.reel("tables.prise.taille-finale", 0.01, 1),
                l.particule("tables.prise.particule"), l.entier("tables.prise.quantite", 0, 200), l.reel("tables.prise.etalement", 0, 2),
                l.reel("tables.prise.vitesse", 0, 5), l.entier("tables.prise.pause", 0, 200));
        Enchantement ench = new Enchantement(l.reel("tables.enchantement.montee", 0, 4), l.entier("tables.enchantement.duree-montee", 1, 200),
                l.entier("tables.enchantement.maintien", 1, 400), l.entier("tables.enchantement.duree-descente", 1, 200),
                l.particule("tables.enchantement.particule"), l.entier("tables.enchantement.quantite", 0, 1000),
                l.reel("tables.enchantement.vitesse", 0, 5), l.reel("tables.enchantement.hauteur-texte", 0, 3),
                l.reel("tables.enchantement.taille-texte", 0.1, 4));
        Map<Material, Table> tables = new EnumMap<>(Material.class);
        for (String id : l.entrees("tables.blocs")) {
            String p = "tables.blocs." + id + ".";
            if (!l.bool(p + "actif")) {
                continue;
            }
            Table t = table(l, id, p);
            for (Material m : l.materiaux(p + "blocs", l.textes(p + "blocs"))) {
                tables.put(m, t);
            }
        }
        Tables lesTables = new Tables(l.bool("tables.actif"), l.reel("tables.portee", 1, 64), l.entier("tables.verification", 1, 200),
                prise, l.bool("tables.retour"), l.reel("tables.taille-nom", 0.1, 4), ench, tables);

        Map<Material, Conteneur> conteneurs = new EnumMap<>(Material.class);
        for (String id : l.entrees("conteneurs.blocs")) {
            String p = "conteneurs.blocs." + id + ".";
            if (!l.bool(p + "actif")) {
                continue;
            }
            String acces = l.texte(p + "acces").toLowerCase(Locale.ROOT);
            if (!acces.equals("dessus") && !acces.equals("face")) {
                throw new IllegalArgumentException(p + "acces : « dessus » ou « face », pas « " + acces + " »");
            }
            boolean dessus = acces.equals("dessus");
            Conteneur c = new Conteneur(id, dessus, dessus ? l.reel(p + "hauteur", -1, 3) : 0.5, dessus ? 0 : l.reel(p + "distance", 0, 3));
            for (Material m : l.materiaux(p + "blocs", l.textes(p + "blocs"))) {
                conteneurs.put(m, c);
            }
        }
        Conteneurs lesConteneurs = new Conteneurs(l.bool("conteneurs.actif"), l.reel("conteneurs.portee", 1, 64),
                l.entier("conteneurs.pause", 0, 200), l.bool("conteneurs.menus-de-plugins"), conteneurs);

        Crafter crafter = new Crafter(l.bool("crafter.actif"), l.reel("crafter.portee", 1, 64), l.entier("crafter.maximum-par-joueur", 0, 10000),
                l.reel("crafter.hauteur", -1, 3), l.reel("crafter.ecart", 0.01, 0.5), l.materiau("crafter.case-fermee"),
                l.entier("crafter.verification", 1, 1200));

        Barre barre = new Barre(l.reel("spawner.barre.largeur", 0.05, 3), l.reel("spawner.barre.epaisseur", 0.005, 1),
                l.reel("spawner.barre.distance", 0, 3), l.reel("spawner.barre.hauteur", -2, 4), l.couleur("spawner.barre.fond"),
                l.couleur("spawner.barre.remplissage"), l.bool("spawner.barre.a-travers-les-blocs"),
                l.entier("spawner.barre.positions", 4, 64), l.entier("spawner.barre.glissement", 0, 59),
                l.reel("spawner.barre.suivi", 0, 64));
        Spawner spawner = new Spawner(l.bool("spawner.actif"), l.reel("spawner.portee", 1, 64), l.entier("spawner.maximum-par-joueur", 0, 10000),
                l.entier("spawner.verification", 1, 200), l.entier("spawner.tolerance", 1, 400), barre, l.bool("spawner.bedrock"));

        List<Pattern> ignorer = new ArrayList<>();
        for (String s : l.textes("banniere.ignorer")) {
            try {
                ignorer.add(Pattern.compile(s));
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("banniere.ignorer : « " + s + " » n'est pas une expression valide");
            }
        }
        Banniere banniere = new Banniere(l.bool("banniere.actif"), l.reel("banniere.portee", 1, 64), l.reel("banniere.hauteur", -2, 6),
                l.reel("banniere.hauteur-murale", -2, 6), l.reel("banniere.taille", 0.1, 4), l.bool("banniere.a-travers-les-blocs"), ignorer);

        Jukebox jukebox = new Jukebox(l.bool("jukebox.actif"), l.reel("jukebox.portee", 1, 64), l.reel("jukebox.hauteur", -2, 6),
                l.bool("jukebox.titre"), l.reel("jukebox.hauteur-titre", -2, 6), l.reel("jukebox.taille-titre", 0.1, 4),
                l.entier("jukebox.verification", 1, 1200));

        Notes notes = new Notes(l.bool("bloc-musical.actif"), l.reel("bloc-musical.portee", 1, 64), l.entier("bloc-musical.duree", 1, 1200),
                l.reel("bloc-musical.distance", 0, 3), l.reel("bloc-musical.taille", 0.1, 4));

        double max = Math.max(Math.max(lesTables.portee(), lesConteneurs.portee()), Math.max(crafter.portee(), spawner.portee()));
        max = Math.max(max, Math.max(banniere.portee(), Math.max(jukebox.portee(), notes.portee())));
        return new Reglages(joueurs, objets, lesTables, lesConteneurs, crafter, spawner, banniere, jukebox, notes,
                new Etiquettes(l.entier("etiquettes.maximum-par-joueur", 0, 10000), l.couleur("etiquettes.fond"), l.bool("etiquettes.ombre"), orientation(l)),
                l.entier("blocs.relecture", 20, 72000),
                l.entier("blocs.verification", 1, 72000), Math.max(1, (int) Math.ceil(max / 16.0)));
    }

    private static Table table(Lecteur l, String id, String p) {
        String m = l.texte(p + "mode").toUpperCase(Locale.ROOT);
        Mode mode;
        try {
            mode = Mode.valueOf(m);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(p + "mode : « pose », « unique » ou « enchantement », pas « " + m.toLowerCase(Locale.ROOT) + " »");
        }
        double hauteur = l.reel(p + "hauteur", -1, 4);
        List<Case> cases = new ArrayList<>();
        if (mode == Mode.POSE) {
            for (String n : l.entrees(p + "cases")) {
                String c = p + "cases." + n + ".";
                cases.add(new Case(numero(p + "cases", n), l.reel(c + "droite", -2, 2), l.reel(c + "fond", -2, 2),
                        l.existe(c + "tourne") ? l.reel(c + "tourne", -180, 180) : 0f));
            }
            if (cases.isEmpty()) {
                throw new IllegalArgumentException(p + "cases : au moins une case");
            }
            return new Table(id, mode, null, hauteur, 0, false, 0, List.copyOf(cases), l.entier(p + "resultat.case", 0, 100),
                    l.reel(p + "resultat.hauteur", -1, 5), l.bool(p + "resultat.nom"));
        }
        for (Integer n : l.entiers(p + "cases")) {
            cases.add(new Case(n, 0, 0, 0));
        }
        if (cases.isEmpty()) {
            throw new IllegalArgumentException(p + "cases : au moins une case");
        }
        if (mode == Mode.ENCHANTEMENT) {
            return new Table(id, mode, Presentation.FLOTTANT, hauteur, 1f, true, 0, List.copyOf(cases), -1, hauteur, false);
        }
        String pr = l.texte(p + "presentation").toUpperCase(Locale.ROOT);
        Presentation presentation;
        try {
            presentation = Presentation.valueOf(pr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(p + "presentation : « debout », « flottant » ou « carte », pas « " + pr.toLowerCase(Locale.ROOT) + " »");
        }
        return new Table(id, mode, presentation, hauteur, l.existe(p + "taille") ? l.reel(p + "taille", 0.05, 4) : 1f,
                !l.existe(p + "tourne") || l.bool(p + "tourne"), l.existe(p + "eclats") ? l.entier(p + "eclats", 0, 200) : 0,
                List.copyOf(cases), l.entier(p + "resultat.case", 0, 100), hauteur, false);
    }

    private static int numero(String cle, String n) {
        try {
            return Integer.parseInt(n);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(cle + " : « " + n + " » n'est pas un numéro de case");
        }
    }

    /** Lit une clé dans le fichier du serveur, sinon dans celui du jar, et dit laquelle est fausse. */
    private static final class Lecteur {
        private final YamlConfiguration fichier, defauts;
        private final Consumer<String> avertir;

        Lecteur(YamlConfiguration fichier, YamlConfiguration defauts, Consumer<String> avertir) {
            this.fichier = fichier;
            this.defauts = defauts;
            this.avertir = avertir;
        }

        boolean existe(String cle) {
            return fichier.get(cle) != null || defauts.get(cle) != null;
        }

        private Object valeur(String cle) {
            Object v = fichier.get(cle);
            if (v == null) {
                v = defauts.get(cle);
            }
            if (v == null) {
                throw new IllegalArgumentException("« " + cle + " » manque");
            }
            return v;
        }

        boolean bool(String cle) {
            if (valeur(cle) instanceof Boolean b) {
                return b;
            }
            throw new IllegalArgumentException("« " + cle + " » : true ou false");
        }

        int entier(String cle, int min, int max) {
            if (valeur(cle) instanceof Number n && n.doubleValue() == n.intValue() && n.intValue() >= min && n.intValue() <= max) {
                return n.intValue();
            }
            throw new IllegalArgumentException("« " + cle + " » : un nombre entier de " + min + " à " + max);
        }

        float reel(String cle, double min, double max) {
            if (valeur(cle) instanceof Number n && n.doubleValue() >= min && n.doubleValue() <= max) {
                return n.floatValue();
            }
            throw new IllegalArgumentException("« " + cle + " » : un nombre de " + min + " à " + max);
        }

        String texte(String cle) {
            return String.valueOf(valeur(cle));
        }

        List<String> textes(String cle) {
            if (valeur(cle) instanceof List<?> liste) {
                List<String> out = new ArrayList<>(liste.size());
                for (Object o : liste) {
                    out.add(String.valueOf(o));
                }
                return out;
            }
            throw new IllegalArgumentException("« " + cle + " » : une liste");
        }

        List<Integer> entiers(String cle) {
            List<Integer> out = new ArrayList<>();
            for (String s : textes(cle)) {
                out.add(numero(cle, s));
            }
            return out;
        }

        /** Noms des entrées d'une section : celles du serveur si la section y est, sinon celles du jar. */
        List<String> entrees(String cle) {
            ConfigurationSection s = fichier.getConfigurationSection(cle);
            if (s == null) {
                s = defauts.getConfigurationSection(cle);
            }
            if (s == null) {
                throw new IllegalArgumentException("« " + cle + " » manque");
            }
            return new ArrayList<>(s.getKeys(false));
        }

        Material materiau(String cle) {
            Material m = Material.matchMaterial(texte(cle));
            if (m == null) {
                throw new IllegalArgumentException("« " + cle + " » : « " + texte(cle) + " » n'est pas un objet du jeu");
            }
            return m;
        }

        /** Noms exacts ou avec « * ». Un nom que ce serveur ne connaît pas est signalé sans bloquer. */
        Set<Material> materiaux(String cle, List<String> motifs) {
            Set<Material> out = EnumSet.noneOf(Material.class);
            for (String motif : motifs) {
                String nom = motif.trim().toUpperCase(Locale.ROOT);
                int avant = out.size();
                if (nom.indexOf('*') < 0) {
                    Material m = Material.getMaterial(nom);
                    if (m != null) {
                        out.add(m);
                    }
                } else {
                    Pattern p = Pattern.compile(Pattern.quote(nom).replace("*", "\\E.*\\Q"));
                    for (Material m : Material.values()) {
                        if (!m.isLegacy() && p.matcher(m.name()).matches()) {
                            out.add(m);
                        }
                    }
                }
                if (out.size() == avant && Material.getMaterial(nom) == null) {
                    avertir.accept("config.yml, " + cle + " : « " + motif + " » ne correspond à rien sur cette version du jeu");
                }
            }
            return out;
        }

        /** Nom d'un son du jeu, ou vide. Seule sa forme est vérifiée : un son que le jeu ne connaît pas ne s'entend pas, c'est tout. */
        String son(String cle) {
            String s = texte(cle).trim();
            if (!s.isEmpty() && org.bukkit.NamespacedKey.fromString(s) == null) {
                throw new IllegalArgumentException("« " + cle + " » : « " + s + " » n'est pas un nom de son (en minuscules, comme entity.item.pickup)");
            }
            return s;
        }

        Particle particule(String cle) {
            Particle p;
            try {
                p = Particle.valueOf(texte(cle).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("« " + cle + " » : « " + texte(cle) + " » n'est pas une particule du jeu");
            }
            // celles qui demandent une couleur, un bloc ou un objet ne peuvent pas être lancées telles quelles
            if (p.getDataType() != Void.class) {
                throw new IllegalArgumentException("« " + cle + " » : la particule « " + texte(cle) + " » a besoin d'un réglage en plus (couleur, bloc...), en choisir une autre");
            }
            return p;
        }

        Color couleur(String cle) {
            String s = texte(cle).trim();
            try {
                if (s.startsWith("#") && s.length() == 7) {
                    return Color.fromARGB(0xFF000000 | Integer.parseInt(s.substring(1), 16));
                }
                if (s.startsWith("#") && s.length() == 9) {
                    return Color.fromARGB((int) Long.parseLong(s.substring(1), 16));
                }
            } catch (NumberFormatException ignored) {
                // message commun plus bas
            }
            throw new IllegalArgumentException("« " + cle + " » : une couleur \"#RRGGBB\" ou \"#AARRGGBB\"");
        }
    }
}
