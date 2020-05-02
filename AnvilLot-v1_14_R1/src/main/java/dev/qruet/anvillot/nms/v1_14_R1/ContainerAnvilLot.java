package dev.qruet.anvillot.nms.v1_14_R1;

import dev.qruet.anvillot.bar.v1_14_R1.ExperienceBar;
import dev.qruet.anvillot.bar.v1_14_R1.TooExpensiveBar;
import dev.qruet.anvillot.config.GeneralPresets;
import dev.qruet.anvillot.config.assets.SoundMeta;
import dev.qruet.anvillot.nms.IContainerAnvilLot;
import dev.qruet.anvillot.utils.L;
import dev.qruet.anvillot.utils.ReflectionUtils;
import dev.qruet.anvillot.utils.RepairCostCalculator;
import dev.qruet.anvillot.utils.num.Int;
import net.minecraft.server.v1_14_R1.*;
import org.bukkit.boss.BarFlag;
import org.bukkit.craftbukkit.libs.org.apache.commons.lang3.StringUtils;
import org.bukkit.craftbukkit.v1_14_R1.entity.CraftHumanEntity;
import org.bukkit.craftbukkit.v1_14_R1.entity.CraftPlayer;
import org.bukkit.craftbukkit.v1_14_R1.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * A rewritten form of the anvil container class
 *
 * @author Qruet
 * @version 3.1.0-Beta-SNAPSHOT
 */
public class ContainerAnvilLot extends ContainerAnvil implements IContainerAnvilLot {

    private final IInventory repairInventory;
    private final IInventory resultInventory;

    private int h;

    private final EntityPlayer owner;

    private ExperienceBar expBar;
    private TooExpensiveBar errBar;

    private int maxCost;
    private int repairCost;

    private final PacketPlayOutGameStateChange defaultMode;

    public ContainerAnvilLot(int i, PlayerInventory playerinventory, final ContainerAccess containeraccess) {
        super(i, playerinventory, containeraccess);

        Object rpI = null;
        Object rlI = null;
        try {
            Field repairInventory = ContainerAnvil.class.getDeclaredField("repairInventory");
            ReflectionUtils.makeNonFinal(repairInventory);
            repairInventory.setAccessible(true);

            rpI = repairInventory.get(this);

            Field resultInventory = ContainerAnvil.class.getDeclaredField("resultInventory");
            ReflectionUtils.makeNonFinal(resultInventory);
            resultInventory.setAccessible(true);

            rlI = resultInventory.get(this);

            Field h = ContainerAnvil.class.getDeclaredField("h");
            h.setAccessible(true);
            this.h = (int) h.get(this);
        } catch (IllegalAccessException | NoSuchFieldException e) {
            e.printStackTrace();
        }

        super.maximumRepairCost = Integer.MAX_VALUE;

        repairInventory = (IInventory) rpI;
        resultInventory = (IInventory) rlI;

        owner = ((CraftPlayer) playerinventory.getOwner()).getHandle();

        if (GeneralPresets.EXPERIENCE_BAR_ENABLED) {
            List<BarFlag> expFlagList = new ArrayList<>();
            if (GeneralPresets.ExperienceBarPresets.FOG)
                expFlagList.add(BarFlag.CREATE_FOG);
            if (GeneralPresets.ExperienceBarPresets.DARK_SKY)
                expFlagList.add(BarFlag.DARKEN_SKY);

            expBar = new ExperienceBar(getOwner(), expFlagList.toArray(new BarFlag[0]));
            expBar.enable();
        }

        if (GeneralPresets.TOO_EXPENSIVE_BAR_ENABLED) {
            List<BarFlag> errFlagList = new ArrayList<>();
            if (GeneralPresets.TooExpensiveBarPresets.FOG)
                errFlagList.add(BarFlag.CREATE_FOG);
            if (GeneralPresets.TooExpensiveBarPresets.DARK_SKY)
                errFlagList.add(BarFlag.DARKEN_SKY);
            errBar = new TooExpensiveBar(this, errFlagList.toArray(new BarFlag[0]));
        }

        super.slots.set(2, new Slot(this.resultInventory, 2, 134, 47) {
            public boolean isAllowed(ItemStack itemstack) {
                return false;
            }

            public boolean isAllowed(EntityHuman entityhuman) {
                return (entityhuman.abilities.canInstantlyBuild || entityhuman.expLevel >= repairCost) && this.hasItem();
            }

            public ItemStack a(EntityHuman entityhuman, ItemStack itemstack) {
                if (!entityhuman.abilities.canInstantlyBuild) {
                    entityhuman.levelDown(-repairCost);
                    if (expBar != null)
                        expBar.update();
                }

                PacketPlayOutSetSlot packet = new PacketPlayOutSetSlot(-1, -1, playerinventory.getCarried());
                owner.playerConnection.sendPacket(packet);

                repairInventory.setItem(0, ItemStack.a);
                if (h > 0) {
                    ItemStack itemstack1 = repairInventory.getItem(1);
                    if (!itemstack1.isEmpty() && itemstack1.getCount() > h) {
                        itemstack1.subtract(h);
                        repairInventory.setItem(1, itemstack1);
                    } else {
                        repairInventory.setItem(1, ItemStack.a);
                    }
                } else {
                    repairInventory.setItem(1, ItemStack.a);
                }

                updateCost(0);
                containeraccess.a((world, blockposition) -> {
                    IBlockData iblockdata = world.getType(blockposition);
                    if (!entityhuman.abilities.canInstantlyBuild && iblockdata.a(TagsBlock.ANVIL) && entityhuman.getRandom().nextFloat() < 0.12F) {
                        IBlockData iblockdata1 = BlockAnvil.a_(iblockdata);
                        if (iblockdata1 == null) {
                            world.a(blockposition, false);
                            world.triggerEffect(1029, blockposition, 0);
                        } else {
                            world.setTypeAndData(blockposition, iblockdata1, 2);
                            world.triggerEffect(1030, blockposition, 0);
                        }
                    } else {
                        world.triggerEffect(1030, blockposition, 0);
                    }

                });
                return itemstack;
            }
        });

        L.R(new Listener() {
            @EventHandler
            public void onDamage(EntityDamageEvent e) {
                if (!(e.getEntity() instanceof Player))
                    return;
                Player player = (Player) e.getEntity();
                if (player.getUniqueId().equals(getOwner().getUniqueId())) {
                    player.closeInventory();
                    HandlerList.unregisterAll(this);
                }
            }
        });

        getOwner().getEffectivePermissions().stream().forEach(pI -> {
            String permission = pI.getPermission();
            if (!permission.startsWith("anvillot.limit."))
                return;
            this.maxCost = Int.P(permission.substring(permission.length() - 1));
        });

        if (maxCost == 0)
            maxCost = GeneralPresets.DEFAULT_MAX_COST;

        defaultMode = new PacketPlayOutGameStateChange(3, 3);
        owner.playerConnection.sendPacket(defaultMode);

        this.repairCost = levelCost.get();
    }

    @Override
    public void transferTo(Container other, CraftHumanEntity player) {
        if (expBar != null)
            expBar.destroy();
        if (errBar != null)
            errBar.destroy();
        reset();
    }

    @Override
    public boolean canUse(EntityHuman entityhuman) {
        return true;
    }

    @Override
    public ItemStack a(int i, int j, InventoryClickType inventoryclicktype, EntityHuman entityhuman) {
        if (!(i == 0 || i == 1) && inventoryclicktype == InventoryClickType.PICKUP) {
            if (getOwner().getLevel() < repairCost)
                return super.a(i, j, inventoryclicktype, entityhuman);
        }
        e();
        return super.a(i, j, inventoryclicktype, entityhuman);
    }

    @Override
    public void e() {
        super.e();

        ItemStack first = repairInventory.getItem(0);
        ItemStack second = repairInventory.getItem(1);
        ItemStack result = resultInventory.getItem(0);

        if (repairCost >= 40) {
            PacketPlayOutGameStateChange packet = new PacketPlayOutGameStateChange(3, 1);
            owner.playerConnection.sendPacket(packet);
        } else {
            owner.playerConnection.sendPacket(defaultMode);
        }

        if (maxCost != -1 && levelCost.get() > maxCost) {
            updateCost(maxCost);
        } else {
            int rPa = first.getRepairCost();
            int rPb = RepairCostCalculator.calculateCost(CraftItemStack.asBukkitCopy(second));

            int bonus = 0;
            if (!StringUtils.isEmpty(renameText)) {
                bonus++;
            }
            updateCost((rPa + rPb) + bonus); //update current repair cost

            if (!result.isEmpty())
                result.setRepairCost((int) (Math.max(rPa, rPb) * 1.4f)); //increment result item's repair cost
        }

        PacketPlayOutSetSlot spack = new PacketPlayOutSetSlot(windowId, 2, resultInventory.getItem(0));
        owner.playerConnection.sendPacket(spack);
    }

    public void updateCost(int val) {
        repairCost = val;
        levelCost.set(val);
        expBar.update();
        if (getOwner().getLevel() < repairCost) {
            PacketPlayOutGameStateChange packet = new PacketPlayOutGameStateChange(3, 3);
            owner.playerConnection.sendPacket(packet);

            levelCost.set(40);

            if (errBar != null) {
                if (!errBar.isEnabled())
                    errBar.enable();
                else
                    errBar.update();
            }

            resultInventory.setItem(0, ItemStack.a);

            if (GeneralPresets.TOO_EXPENSIVE_ALERT_ENABLED) {
                SoundMeta sM = GeneralPresets.TOO_EXPENSIVE_ALERT;
                if (sM != null) {
                    getOwner().playSound(
                            getOwner().getLocation(),
                            sM.getSound(),
                            sM.getVolume(),
                            sM.getPitch());
                }
            }
            return;
        }
        if (errBar != null && errBar.isEnabled())
            errBar.disable();
    }

    public int getCost() {
        return repairCost;
    }

    public Player getOwner() {
        return owner.getBukkitEntity();
    }

    private void reset() {
        PacketPlayOutGameStateChange packet = new PacketPlayOutGameStateChange(3, owner.playerInteractManager.getGameMode().getId());
        owner.playerConnection.sendPacket(packet);
    }


}