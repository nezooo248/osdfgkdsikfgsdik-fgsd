package fr.clan.model;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Represente un clan : un chef, des bras droits (officers) et des membres.
 * L'ensemble "members" contient TOUT le monde (chef + bras droits + membres simples).
 */
public class Clan {

    public enum Role {
        LEADER,   // Chef
        OFFICER,  // Bras droit
        MEMBER    // Membre simple
    }

    private String name;
    private UUID leader;
    private final Set<UUID> members = new LinkedHashSet<>();
    private final Set<UUID> officers = new LinkedHashSet<>();

    public Clan(String name, UUID leader) {
        this.name = name;
        this.leader = leader;
        this.members.add(leader);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UUID getLeader() {
        return leader;
    }

    public void setLeader(UUID leader) {
        this.leader = leader;
    }

    /** Tous les membres (chef inclus). */
    public Set<UUID> getMembers() {
        return members;
    }

    /** Uniquement les bras droits. */
    public Set<UUID> getOfficers() {
        return officers;
    }

    public int size() {
        return members.size();
    }

    public boolean isMember(UUID uuid) {
        return members.contains(uuid);
    }

    public boolean isLeader(UUID uuid) {
        return leader.equals(uuid);
    }

    public boolean isOfficer(UUID uuid) {
        return officers.contains(uuid);
    }

    public Role getRole(UUID uuid) {
        if (leader.equals(uuid)) return Role.LEADER;
        if (officers.contains(uuid)) return Role.OFFICER;
        if (members.contains(uuid)) return Role.MEMBER;
        return null;
    }

    public void addMember(UUID uuid) {
        members.add(uuid);
    }

    public void removeMember(UUID uuid) {
        members.remove(uuid);
        officers.remove(uuid);
    }

    /** Passe un membre simple en bras droit. */
    public void promote(UUID uuid) {
        if (members.contains(uuid) && !leader.equals(uuid)) {
            officers.add(uuid);
        }
    }

    /** Repasse un bras droit en membre simple. */
    public void demote(UUID uuid) {
        officers.remove(uuid);
    }

    // ---- Regles de permissions internes au clan ----

    public boolean canInvite(UUID uuid) {
        return isLeader(uuid) || isOfficer(uuid);
    }

    public boolean canKick(UUID uuid) {
        return isLeader(uuid) || isOfficer(uuid);
    }

    /** Seul le chef gere les grades (promouvoir / retrograder). */
    public boolean canManageRoles(UUID uuid) {
        return isLeader(uuid);
    }
}
