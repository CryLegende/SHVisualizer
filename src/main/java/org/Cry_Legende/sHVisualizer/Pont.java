package org.Cry_Legende.sHVisualizer;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Les quelques rouages du serveur qu'il faut pour montrer une entité à des joueurs sans qu'elle existe dans le monde :
 * lire ses données, fabriquer les paquets d'apparition, de données, de déplacement et de retrait, les envoyer.
 * <p>
 * Tout est cherché une fois, par son nom Mojang. C'est le serveur lui-même qui écrit les données de l'entité : le
 * plugin ne connaît ni le protocole ni les numéros de champs, qui changent d'une version à l'autre. Sur un serveur où
 * un nom manque, {@link #creer()} échoue et le plugin n'affiche rien.
 */
final class Pont {

    private final MethodHandle getHandle;
    private final MethodHandle getEntityData;
    private final MethodHandle getType;
    private final MethodHandle nonDefault;
    private final MethodHandle packDirty;
    private final MethodHandle newAdd;
    private final MethodHandle newData;
    private final MethodHandle newRemove;
    private final MethodHandle newBundle;
    private final MethodHandle getConnection;
    private final MethodHandle send;
    private final Object immobile;
    /** Déplacement d'une entité déjà montrée ; null sur un serveur qui le nomme autrement (elle est alors remontrée). */
    private final Deplacement deplacement;
    /** Marque « pas dans le monde » d'une entité ; null sur un serveur qui ne l'a pas. */
    private final Field generation;

    private Pont() throws ReflectiveOperationException {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        String craftBukkit = Bukkit.getServer().getClass().getPackage().getName();

        Method handle = Class.forName(craftBukkit + ".entity.CraftEntity").getMethod("getHandle");
        Class<?> entity = handle.getReturnType();
        getHandle = lookup.unreflect(handle);

        Method entityData = entity.getMethod("getEntityData");
        getEntityData = lookup.unreflect(entityData);
        nonDefault = lookup.unreflect(entityData.getReturnType().getMethod("getNonDefaultValues"));
        packDirty = lookup.unreflect(entityData.getReturnType().getMethod("packDirty"));

        Method type = entity.getMethod("getType");
        getType = lookup.unreflect(type);

        ClassLoader serveur = entity.getClassLoader();
        Class<?> vec3 = Class.forName("net.minecraft.world.phys.Vec3", true, serveur);
        immobile = vec3.getField("ZERO").get(null);

        String paquets = "net.minecraft.network.protocol.game.";
        newAdd = lookup.findConstructor(Class.forName(paquets + "ClientboundAddEntityPacket", true, serveur),
                MethodType.methodType(void.class, int.class, UUID.class, double.class, double.class, double.class,
                        float.class, float.class, type.getReturnType(), int.class, vec3, double.class));
        newData = lookup.findConstructor(Class.forName(paquets + "ClientboundSetEntityDataPacket", true, serveur),
                MethodType.methodType(void.class, int.class, List.class));
        newRemove = lookup.findConstructor(Class.forName(paquets + "ClientboundRemoveEntitiesPacket", true, serveur),
                MethodType.methodType(void.class, int[].class));
        newBundle = lookup.findConstructor(Class.forName(paquets + "ClientboundBundlePacket", true, serveur),
                MethodType.methodType(void.class, Iterable.class));

        Class<?> serverPlayer = Class.forName(craftBukkit + ".entity.CraftPlayer").getMethod("getHandle").getReturnType();
        Field connection = serverPlayer.getField("connection");
        getConnection = lookup.unreflectGetter(connection);
        send = lookup.unreflect(connection.getType().getMethod("send", Class.forName("net.minecraft.network.protocol.Packet", true, serveur)));

        Deplacement d;
        try {
            d = new Deplacement(lookup, paquets, vec3, immobile, serveur);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            d = null;
        }
        deplacement = d;

        Field g;
        try {
            g = entity.getField("generation");
            if (g.getType() != boolean.class) {
                g = null;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            g = null;
        }
        generation = g;
    }

    /** Le paquet qui déplace une entité montrée. */
    private static final class Deplacement {
        private final MethodHandle newVec3;
        private final MethodHandle newPosition;
        private final MethodHandle newTeleport;
        private final Object immobile;

        Deplacement(MethodHandles.Lookup lookup, String paquets, Class<?> vec3, Object immobile, ClassLoader serveur) throws ReflectiveOperationException {
            Class<?> position = Class.forName("net.minecraft.world.entity.PositionMoveRotation", true, serveur);
            newVec3 = lookup.findConstructor(vec3, MethodType.methodType(void.class, double.class, double.class, double.class));
            newPosition = lookup.findConstructor(position, MethodType.methodType(void.class, vec3, vec3, float.class, float.class));
            newTeleport = lookup.findConstructor(Class.forName(paquets + "ClientboundTeleportEntityPacket", true, serveur),
                    MethodType.methodType(void.class, int.class, position, Set.class, boolean.class));
            this.immobile = immobile;
        }

        Object paquet(int id, double x, double y, double z) throws Throwable {
            Object ou = newVec3.invoke(x, y, z);
            return newTeleport.invoke(id, newPosition.invoke(ou, immobile, 0f, 0f), Set.of(), false);
        }
    }

    /** @throws ReflectiveOperationException quand ce serveur n'expose pas ce qu'il faut */
    static Pont creer() throws ReflectiveOperationException {
        return new Pont();
    }

    /**
     * Dit au serveur que cette entité n'est pas dans le monde : un cadre réglé ensuite ne réveille aucun bloc voisin et
     * ne joue aucun son. Sans effet sur un serveur qui ne connaît pas cette marque.
     */
    void horsDuMonde(Entity entite) {
        if (generation != null) {
            try {
                generation.setBoolean(handle(entite), true);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // le cadre s'affiche quand même
            }
        }
    }

    /** L'objet serveur d'une entité. */
    Object handle(Entity entite) {
        try {
            return getHandle.invoke(entite);
        } catch (Throwable e) {
            throw echec(e);
        }
    }

    /** Paquet d'apparition d'une entité à un endroit. « donnee » : la face d'un cadre, 0 pour un display. */
    Object apparition(Object handle, int id, UUID uuid, double x, double y, double z, float pitch, float yaw, int donnee) {
        try {
            return newAdd.invoke(id, uuid, x, y, z, pitch, yaw, getType.invoke(handle), donnee, immobile, 0.0d);
        } catch (Throwable e) {
            throw echec(e);
        }
    }

    /** Tout ce qui distingue l'entité d'une entité neuve de son type : ce que reçoit un joueur qui la découvre. */
    List<?> valeurs(Object handle) {
        try {
            List<?> v = (List<?>) nonDefault.invoke(getEntityData.invoke(handle));
            return v == null ? List.of() : v;
        } catch (Throwable e) {
            throw echec(e);
        }
    }

    /** Ce qui a changé depuis le dernier appel, null si rien : ce que reçoivent les joueurs qui la voient déjà. */
    List<?> changements(Object handle) {
        try {
            return (List<?>) packDirty.invoke(getEntityData.invoke(handle));
        } catch (Throwable e) {
            throw echec(e);
        }
    }

    Object donnees(int id, List<?> valeurs) {
        try {
            return newData.invoke(id, valeurs);
        } catch (Throwable e) {
            throw echec(e);
        }
    }

    Object retrait(int... ids) {
        try {
            return newRemove.invoke(ids);
        } catch (Throwable e) {
            throw echec(e);
        }
    }

    /** Plusieurs paquets que le client applique dans la même image. */
    Object lot(List<Object> paquets) {
        try {
            return paquets.size() == 1 ? paquets.get(0) : newBundle.invoke((Iterable<Object>) paquets);
        } catch (Throwable e) {
            throw echec(e);
        }
    }

    boolean deplace() {
        return deplacement != null;
    }

    /** Paquet qui déplace une entité montrée ; à n'appeler que si {@link #deplace()}. */
    Object deplacement(int id, double x, double y, double z) {
        try {
            return deplacement.paquet(id, x, y, z);
        } catch (Throwable e) {
            throw echec(e);
        }
    }

    /** La connexion d'un joueur, à garder le temps de sa session ; null s'il n'en a pas encore. */
    Object connexion(Player joueur) {
        try {
            return getConnection.invoke(getHandle.invoke(joueur));
        } catch (Throwable e) {
            throw echec(e);
        }
    }

    void envoyer(Object connexion, Object paquet) {
        try {
            send.invoke(connexion, paquet);
        } catch (Throwable e) {
            throw echec(e);
        }
    }

    private static RuntimeException echec(Throwable e) {
        if (e instanceof RuntimeException r) {
            return r;
        }
        if (e instanceof Error err) {
            throw err;
        }
        return new IllegalStateException(e);
    }
}
