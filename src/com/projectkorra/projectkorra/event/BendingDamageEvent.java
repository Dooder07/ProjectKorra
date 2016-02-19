package com.projectkorra.projectkorra.event;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class BendingDamageEvent extends Event implements Cancellable {
	
	private static final HandlerList handlers = new HandlerList();
	private boolean cancelled;
	private double damage;
	private String ability;
	
	public BendingDamageEvent(double damage, String ability) {
		this.cancelled = false;
		this.damage = damage;
		this.ability = ability;
	}
	
	public String getAbility() {
		return ability;
	}
	
	public void setAbility(String ability) {
		this.ability = ability;
	}
	
	public double getDamage() {
		return damage;
	}
	
	public void setDamage(double damage) {
		this.damage = damage;
	}

	@Override
	public HandlerList getHandlers() {
		return handlers;
	}

	@Override
	public boolean isCancelled() {
		return cancelled;
	}

	@Override
	public void setCancelled(boolean cancel) {
		this.cancelled = cancel;
	}

}
