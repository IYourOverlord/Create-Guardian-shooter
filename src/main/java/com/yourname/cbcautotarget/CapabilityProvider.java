package com.yourname.cbcautotarget;

import com.yourname.cbcautotarget.blockentity.ControllerBlockEntity;
import net.minecraft.core.Direction;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

@EventBusSubscriber(modid = CBCAutoTarget.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public class CapabilityProvider {

    @SubscribeEvent
    public static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                ModBlockEntities.CONTROLLER.get(),
                (be, side) -> be.getInventory()
        );
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                ModBlockEntities.CARTRIDGE_COLLECTOR.get(),
                (be, side) -> new net.neoforged.neoforge.items.ItemStackHandler(be.getInventory().size()) {
                    @Override
                    public int getSlots() { return be.getInventory().size(); }
                    @Override
                    public net.minecraft.world.item.ItemStack getStackInSlot(int slot) {
                        return slot < be.getInventory().size() ? be.getInventory().get(slot) : net.minecraft.world.item.ItemStack.EMPTY;
                    }
                    @Override
                    public net.minecraft.world.item.ItemStack extractItem(int slot, int amount, boolean simulate) {
                        if (slot >= be.getInventory().size()) return net.minecraft.world.item.ItemStack.EMPTY;
                        net.minecraft.world.item.ItemStack stack = be.getInventory().get(slot);
                        if (stack.isEmpty()) return net.minecraft.world.item.ItemStack.EMPTY;
                        int toExtract = Math.min(amount, stack.getCount());
                        net.minecraft.world.item.ItemStack extracted = stack.copyWithCount(toExtract);
                        if (!simulate) {
                            stack.shrink(toExtract);
                            if (stack.isEmpty()) be.getInventory().remove(slot);
                            be.setChanged();
                        }
                        return extracted;
                    }
                    @Override
                    public net.minecraft.world.item.ItemStack insertItem(int slot, net.minecraft.world.item.ItemStack stack, boolean simulate) {
                        return stack; // только выдача, не приём
                    }
                    @Override
                    public boolean isItemValid(int slot, net.minecraft.world.item.ItemStack stack) { return false; }
                }
        );
    }
}
