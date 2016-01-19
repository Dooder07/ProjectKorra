package com.projectkorra.projectkorra;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import com.projectkorra.projectkorra.Element.SubElement;
import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.ability.ChiAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.FireAbility;
import com.projectkorra.projectkorra.ability.WaterAbility;
import com.projectkorra.projectkorra.avatar.AvatarState;
import com.projectkorra.projectkorra.chiblocking.Paralyze;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.earthbending.MetalClips;
import com.projectkorra.projectkorra.event.PlayerCooldownChangeEvent;
import com.projectkorra.projectkorra.event.PlayerCooldownChangeEvent.Result;
import com.projectkorra.projectkorra.storage.DBConnection;
import com.projectkorra.projectkorra.waterbending.Bloodbending;

/**
 * Class that presents a player and stores all bending information about the player.
 */
public class BendingPlayer {

	/**
	 * ConcurrentHashMap that contains all instances of BendingPlayer, with UUID key.
	 */
	private static final ConcurrentHashMap<UUID, BendingPlayer> PLAYERS = new ConcurrentHashMap<>();

	private boolean permaRemoved;
	private boolean toggled;
	private boolean tremorSense;
	private boolean chiBlocked;
	private long slowTime;
	private Player player;
	private UUID uuid;
	private String name;
	private ChiAbility stance;
	private ArrayList<Element> elements;
	private HashMap<Integer, String> abilities;
	private ConcurrentHashMap<String, Long> cooldowns;
	private ConcurrentHashMap<Element, Boolean> toggledElements;	

	/**
	 * Creates a new {@link BendingPlayer}.
	 * 
	 * @param uuid The unique identifier
	 * @param playerName The playername
	 * @param elements The known elements
	 * @param abilities The known abilities
	 * @param permaRemoved The permanent removed status
	 */
	public BendingPlayer(UUID uuid, String playerName, ArrayList<Element> elements, HashMap<Integer, String> abilities,
			boolean permaRemoved) {
		this.uuid = uuid;
		this.name = playerName;
		this.elements = elements;
		this.setAbilities(abilities);
		this.permaRemoved = permaRemoved;
		this.player = Bukkit.getPlayer(uuid);
		this.toggled = true;
		this.tremorSense = true;
		this.chiBlocked = false;
		cooldowns = new ConcurrentHashMap<String, Long>();
		toggledElements = new ConcurrentHashMap<Element, Boolean>();
		toggledElements.put(Element.AIR, true);
		toggledElements.put(Element.EARTH, true);
		toggledElements.put(Element.FIRE, true);
		toggledElements.put(Element.WATER, true);
		toggledElements.put(Element.CHI, true);

		PLAYERS.put(uuid, this);
		PKListener.login(this);
	}
	
	public void addCooldown(Ability ability, long cooldown) {
		addCooldown(ability.getName(), cooldown);
	}

	public void addCooldown(Ability ability) {
		addCooldown(ability, ability.getCooldown());
	}

	/**
	 * Adds an ability to the cooldowns map while firing a {@link PlayerCooldownChangeEvent}.
	 * 
	 * @param ability Name of the ability
	 * @param cooldown The cooldown time
	 */
	public void addCooldown(String ability, long cooldown) {
		PlayerCooldownChangeEvent event = new PlayerCooldownChangeEvent(Bukkit.getPlayer(uuid), ability, cooldown, Result.ADDED);
		Bukkit.getServer().getPluginManager().callEvent(event);
		if (!event.isCancelled()) {
			this.cooldowns.put(ability, cooldown + System.currentTimeMillis());
		}
	}

	/**
	 * Adds an element to the {@link BendingPlayer}'s known list.
	 * 
	 * @param e The element to add
	 */
	public void addElement(Element element) {
		this.elements.add(element);
	}

	/**
	 * Sets chiBlocked to true.
	 */
	public void blockChi() {
		chiBlocked = true;
	}

	/**
	 * Checks to see if a Player is effected by BloodBending.
	 * 
	 * @return true If {@link ChiMethods#isChiBlocked(String)} is true <br />
	 *         false If player is BloodBender and Bending is toggled on, or if player is in
	 *         AvatarState
	 */
	public boolean canBeBloodbent() {
		if (isAvatarState()) {
			if (isChiBlocked()) {
				return true;
			}
		}
		if (canBendIgnoreBindsCooldowns(CoreAbility.getAbility("Bloodbending")) && !isToggled()) {
			return false;
		}
		return true;
	}

	public boolean canBend(CoreAbility ability) {
		return canBend(ability, false, false);
	}

	private boolean canBend(CoreAbility ability, boolean ignoreBinds, boolean ignoreCooldowns) {
		if (ability == null) {
			return false;
		}
		
		List<String> disabledWorlds = getConfig().getStringList("Properties.DisabledWorlds");
		Location playerLoc = player.getLocation();
		
		if (!player.isOnline() || player.isDead()) {
			return false;
		} else if (ability.getPlayer() != null && ability.getLocation() != null && !ability.getLocation().getWorld().equals(player.getWorld())) {
			return false;
		} else if (!ignoreCooldowns && isOnCooldown(ability.getName())) {
			return false;
		} else if (!ignoreBinds && !ability.getName().equals(getBoundAbilityName())) {
			return false;
		} else if (disabledWorlds != null && disabledWorlds.contains(player.getWorld().getName())) {
			return false;
		} else if (Commands.isToggledForAll || !isToggled() || !isElementToggled(ability.getElement())) {
			return false;
		} else if (player.getGameMode() == GameMode.SPECTATOR) {
			return false;
		}
		
		if (!ignoreCooldowns && cooldowns.containsKey(name)) {
			if (cooldowns.get(name) + getConfig().getLong("Properties.GlobalCooldown") >= System.currentTimeMillis()) {
				return false;
			}
			cooldowns.remove(name);
		}

		if (isChiBlocked() || isParalyzed() || isBloodbended() || isControlledByMetalClips()) {
			return false;
		} else if (GeneralMethods.isRegionProtectedFromBuild(player, ability.getName(), playerLoc)) {
			return false;
		} else if (ability instanceof FireAbility && FireAbility.isSolarEclipse(player.getWorld())) {
			return false;
		} else if (ability instanceof WaterAbility && WaterAbility.isLunarEclipse(player.getWorld())) {
			return false;
		} 
		
		if (!ignoreBinds && !canBind(ability)) {
			return false;
		}
		return true;
	}

	public boolean canBendIgnoreBinds(CoreAbility ability) {
		return canBend(ability, true, false);
	}

	public boolean canBendIgnoreBindsCooldowns(CoreAbility ability) {
		return canBend(ability, true, true);
	}

	public boolean canBendIgnoreCooldowns(CoreAbility ability) {
		return canBend(ability, false, true);
	}
	
	public boolean canBendPassive(Element element) {
		if (element == null || player == null) {
			return false;
		} else if (!player.hasPermission("bending." + element.getName() + ".passive")) {
			return false;
		} else if (!isToggled() || !hasElement(element) || !isElementToggled(element)) {
			return false;
		} else if (isChiBlocked() || isParalyzed() || isBloodbended()) {
			return false;
		} else if (GeneralMethods.isRegionProtectedFromBuild(player, player.getLocation())) {
			return false;
		} 
		return true;
	}

	/**
	 * Checks to see if {@link BendingPlayer} can be slowed.
	 * 
	 * @return true If player can be slowed
	 */
	public boolean canBeSlowed() {
		return (System.currentTimeMillis() > slowTime);
	}

	public boolean canBind(CoreAbility ability) {
		if (ability == null || !player.isOnline()) {
			return false;
		} else if (!player.hasPermission("bending.ability." + ability.getName())) {
			return false;
		} else if (!hasElement(ability.getElement())) {
			return false;
		} else if (ability.getElement() instanceof SubElement) {
			SubElement subElement = (SubElement) ability.getElement();
			if (!hasElement(subElement.getParentElement())) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Checks to see if a player can BloodBend.
	 * 
	 * @return true If player has permission node "bending.earth.bloodbending"
	 */
	public boolean canBloodbend() {
		return player.hasPermission("bending.water.bloodbending");
	}
	
	public boolean canBloodbendAtAnytime() {
		return canBloodbend() && player.hasPermission("bending.water.bloodbending.anytime");
	}

	public boolean canCombustionbend() {
		return player.hasPermission("bending.fire.combustionbending");
	}

	public boolean canIcebend() {
		return player.hasPermission("bending.water.icebending");

	}

	/**
	 * Checks to see if a player can LavaBend.
	 * 
	 * @param player The player to check
	 * @return true If player has permission node "bending.earth.lavabending"
	 */
	public boolean canLavabend() {
		return player.hasPermission("bending.earth.lavabending");
	}

	public boolean canLightningbend() {
		return player.hasPermission("bending.fire.lightningbending");
	}

	/**
	 * Checks to see if a player can MetalBend.
	 * 
	 * @param player The player to check
	 * @return true If player has permission node "bending.earth.metalbending"
	 */
	public boolean canMetalbend() {
		return player.hasPermission("bending.earth.metalbending");
	}

	public boolean canPackedIcebend() {
		return getConfig().getBoolean("Properties.Water.CanBendPackedIce");
	}

	/**
	 * Checks to see if a player can PlantBend.
	 * 
	 * @param player The player to check
	 * @return true If player has permission node "bending.ability.plantbending"
	 */
	public boolean canPlantbend() {
		return player.hasPermission("bending.water.plantbending");
	}

	/**
	 * Checks to see if a player can SandBend.
	 * 
	 * @param player The player to check
	 * @return true If player has permission node "bending.earth.sandbending"
	 */
	public boolean canSandbend() {
		return player.hasPermission("bending.earth.sandbending");
	}

	/**
	 * Checks to see if a player can use Flight.
	 * 
	 * @return true If player has permission node "bending.air.flight"
	 */
	public boolean canUseFlight() {
		return player.hasPermission("bending.air.flight");
	}

	/**
	 * Checks to see if a player can use SpiritualProjection.
	 * 
	 * @param player The player to check
	 * @return true If player has permission node "bending.air.spiritualprojection"
	 */
	public boolean canUseSpiritualProjection() {
		return player.hasPermission("bending.air.spiritualprojection");
	}

	public boolean canWaterHeal() {
		return player.hasPermission("bending.water.healing");
	}

	/**
	 * Gets the map of abilities that the {@link BendingPlayer} knows.
	 * 
	 * @return map of abilities
	 */
	public HashMap<Integer, String> getAbilities() {
		return this.abilities;
	}

	public CoreAbility getBoundAbility() {
		return CoreAbility.getAbility(getBoundAbilityName());
	}

	/**
	 * Gets the Ability bound to the slot that the player is in.
	 * 
	 * @return The Ability name bounded to the slot
	 */
	public String getBoundAbilityName() {
		int slot = player.getInventory().getHeldItemSlot() + 1;
		String name = getAbilities().get(slot);
		return name != null ? name : "";
	}

	/**
	 * Gets the cooldown time of the ability.
	 * 
	 * @param ability The ability to check
	 * @return the cooldown time
	 *         <p>
	 *         or -1 if cooldown doesn't exist
	 *         </p>
	 */
	public long getCooldown(String ability) {
		if (cooldowns.containsKey(ability)) {
			return cooldowns.get(ability);
		}
		return -1;
	}
	
	/**
	 * Gets the map of cooldowns of the {@link BendingPlayer}.
	 * 
	 * @return map of cooldowns
	 */
	public ConcurrentHashMap<String, Long> getCooldowns() {
		return cooldowns;
	}

	/**
	 * Gets the list of elements the {@link BendingPlayer} knows.
	 * 
	 * @return a list of elements
	 */
	public List<Element> getElements() {
		return this.elements;
	}

	/**
	 * Gets the name of the {@link BendingPlayer}.
	 * 
	 * @return the player name
	 */
	public String getName() {
		return this.name;
	}

	/**
	 * Gets the {@link ChiAbility Chi stance} the player is in
	 * 
	 * @return The player's stance object
	 */
	public ChiAbility getStance() {
		return stance;
	}

	/**
	 * Gets the unique identifier of the {@link BendingPlayer}.
	 * 
	 * @return the uuid
	 */
	public UUID getUUID() {
		return this.uuid;
	}

	/**
	 * Convenience method to {@link #getUUID()} as a string.
	 * 
	 * @return string version of uuid
	 */
	public String getUUIDString() {
		return this.uuid.toString();
	}
	
	/**
	 * Checks to see if the {@link BendingPlayer} knows a specific element.
	 * 
	 * @param element The element to check
	 * @return true If the player knows the element
	 */
	public boolean hasElement(Element element) {
		if (element == null) {
			return false;
		} else if (element == Element.AVATAR) {
			// At the moment we'll allow for both permissions to return true.
			// Later on we can consider deleting the bending.ability.avatarstate option.
			return player.hasPermission("bending.avatar") || player.hasPermission("bending.ability.AvatarState");
		} else if (!(element instanceof SubElement)) {
			return this.elements.contains(element);
		} else {
			Element parentElement = ((SubElement) element).getParentElement();
			String prefix = "bending." + parentElement.getName() + ".";
			
			// Some permissions are bending.water.name and some are bending.water.namebending
			if (player.hasPermission(prefix + element.getName())
					|| player.hasPermission(prefix + element.getName() + "bending")) {
				return true;
			}
		}
		return false;
	}

	public boolean isAvatarState() {
		return CoreAbility.hasAbility(player, AvatarState.class);
	}

	public boolean isBloodbended() {
		return Bloodbending.isBloodbended(player);
	}

	/**
	 * Checks to see if the {@link BendingPlayer} is chi blocked.
	 * 
	 * @return true If the player is chi blocked
	 */
	public boolean isChiBlocked() {
		return this.chiBlocked;
	}

	public boolean isControlledByMetalClips() {
		return MetalClips.isControlled(player);
	}

	public boolean isElementToggled(Element element) {
		if (element != null && toggledElements.containsKey(element)) {
			return toggledElements.containsKey(element);
		}
		return true;
	}

	public boolean isOnCooldown(Ability ability) {
		return isOnCooldown(ability.getName());
	}

	/**
	 * Checks to see if a specific ability is on cooldown.
	 * 
	 * @param ability The ability name to check
	 * @return true if the cooldown map contains the ability
	 */
	public boolean isOnCooldown(String ability) {
		if (this.cooldowns.containsKey(ability)) {
			return System.currentTimeMillis() < cooldowns.get(ability);
		}
		return false;
	}

	public boolean isParalyzed() {
		return Paralyze.isParalyzed(player);
	}

	/**
	 * Checks if the {@link BendingPlayer} is permaremoved.
	 * 
	 * @return true If the player is permaremoved
	 */
	public boolean isPermaRemoved() {
		return this.permaRemoved;
	}

	/**
	 * Checks if the {@link BendingPlayer} has bending toggled on.
	 * 
	 * @return true If bending is toggled on
	 */
	public boolean isToggled() {
		return this.toggled;
	}

	/**
	 * Checks if the {@link BendingPlayer} is tremor sensing.
	 * 
	 * @return true if player is tremor sensing
	 */
	public boolean isTremorSensing() {
		return this.tremorSense;
	}

	public void removeCooldown(CoreAbility ability) {
		if (ability != null) {
			removeCooldown(ability.getName());
		}
	}
	
	/**
	 * Removes the cooldown of an ability.
	 * 
	 * @param ability The ability's cooldown to remove
	 */
	public void removeCooldown(String ability) {
		PlayerCooldownChangeEvent event = new PlayerCooldownChangeEvent(Bukkit.getPlayer(uuid), ability, 0, Result.REMOVED);
		Bukkit.getServer().getPluginManager().callEvent(event);
		if (!event.isCancelled()) {
			this.cooldowns.remove(ability);
		}
	}

	/**
	 * Sets the {@link BendingPlayer}'s abilities. This method also saves the abilities to the
	 * database.
	 * 
	 * @param abilities The abilities to set/save
	 */
	public void setAbilities(HashMap<Integer, String> abilities) {
		this.abilities = abilities;
		for (int i = 1; i <= 9; i++) {
			DBConnection.sql.modifyQuery("UPDATE pk_players SET slot" + i + " = '" + abilities.get(i) + "' WHERE uuid = '" + uuid + "'");
		}
	}

	/**
	 * Sets the {@link BendingPlayer}'s element. If the player had elements before they will be
	 * overwritten.
	 * 
	 * @param e The element to set
	 */
	public void setElement(Element element) {
		this.elements.clear();
		this.elements.add(element);
	}

	/**
	 * Sets the permanent removed state of the {@link BendingPlayer}.
	 * 
	 * @param permaRemoved
	 */
	public void setPermaRemoved(boolean permaRemoved) {
		this.permaRemoved = permaRemoved;
	}
	
	/**
	 * Sets the player's {@link ChiAbility Chi stance}
	 * 
	 * @param stance The player's new stance object
	 */
	public void setStance(ChiAbility stance) {
		this.stance = stance;
	}

	/**
	 * Slow the {@link BendingPlayer} for a certain amount of time.
	 * 
	 * @param cooldown The amount of time to slow.
	 */
	public void slow(long cooldown) {
		slowTime = System.currentTimeMillis() + cooldown;
	}
	
	/**
	 * Toggles the {@link BendingPlayer}'s bending.
	 */
	public void toggleBending() {
		toggled = !toggled;
	}

	public void toggleElement(Element element) {
		if (element == null) {
			return;
		}
		toggledElements.put(element, !toggledElements.get(element));
	}
	
	/**
	 * Toggles the {@link BendingPlayer}'s tremor sensing.
	 */
	public void toggleTremorSense() {
		tremorSense = !tremorSense;
	}

	/**
	 * Sets the {@link BendingPlayer}'s chi blocked to false.
	 */
	public void unblockChi() {
		chiBlocked = false;
	}
	
	public static BendingPlayer getBendingPlayer(OfflinePlayer oPlayer) {
		if (oPlayer == null) {
			return null;
		}
		return BendingPlayer.getPlayers().get(oPlayer.getUniqueId());
	}
	
	public static BendingPlayer getBendingPlayer(Player player) {
		if (player == null) {
			return null;
		}
		return getBendingPlayer(player.getName());
	}
	
	/**
	 * Attempts to get a {@link BendingPlayer} from specified player name. this method tries to get
	 * a {@link Player} object and gets the uuid and then calls {@link #getBendingPlayer(UUID)}
	 * 
	 * @param playerName The name of the Player
	 * @return The BendingPlayer object if {@link BendingPlayer#PLAYERS} contains the player name
	 * 
	 * @see #getBendingPlayer(UUID)
	 */
	public static BendingPlayer getBendingPlayer(String playerName) {
		if (playerName == null) {
			return null;
		}
		Player player = Bukkit.getPlayer(playerName);
		OfflinePlayer oPlayer = player != null ? Bukkit.getOfflinePlayer(player.getUniqueId()) : null;
		return getBendingPlayer(oPlayer);
	}

	private static FileConfiguration getConfig() {
		return ConfigManager.getConfig();
	}
	
	/**
	 * Gets the map of {@link BendingPlayer}s.
	 * 
	 * @return {@link #PLAYERS}
	 */
	public static ConcurrentHashMap<UUID, BendingPlayer> getPlayers() {
		return PLAYERS;
	}
}
