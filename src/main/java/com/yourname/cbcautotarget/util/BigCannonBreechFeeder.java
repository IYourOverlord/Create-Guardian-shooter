package com.yourname.cbcautotarget.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedBigCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.cannons.big_cannons.breeches.quickfiring_breech.CannonMountPoint;
import rbasamoyai.createbigcannons.config.CBCConfigs;
import rbasamoyai.createbigcannons.munitions.big_cannon.BigCannonMunitionBlock;

/**
 * Автоматическая зарядка снарядов и Биг-картриджей в Quick-Firing Breech больших пушек CBC
 * из инвентаря блока-контроллера.
 * <p>
 * В отличие от автопушки ({@code MountedAutocannonContraption}, реализующей {@code GetItemStorage}),
 * большая пушка ({@link MountedBigCannonContraption}) НЕ имеет обычного {@code IItemHandler} —
 * {@code CannonMountBlockEntity#getItemHandler} для неё всегда возвращает {@code null}. Поэтому
 * стандартная insertItem-логика ({@code ControllerBlockEntity#tryTransferToCannon}) её не видит,
 * и через контроллер удавалось заряжать только автопушку.
 * <p>
 * Зарядка снаряда/картриджа в CBC устроена не как вставка предмета в инвентарь, а как замена
 * блока в казённике ({@code BigCannonBehavior#loadBlock}). Чтобы не дублировать эту логику и
 * гарантировать полную совместимость со всеми материалами Quick-Firing Breech (BE один и тот же
 * класс для всех материалов) и корректную последовательность "снаряд -> картридж", мы используем
 * ту же публичную точку входа, что и Mechanical Arm из Create — {@link CannonMountPoint#bigCannonInsert}.
 * Она сама:
 * <ul>
 *     <li>определяет, подключён ли к пушке именно Quick-Firing Breech (иначе просто отклоняет предмет);</li>
 *     <li>не позволяет зарядить картридж, пока в казённике нет снаряда;</li>
 *     <li>при зарядке нового снаряда в казённик, где лежит отстрелянный (пустой) картридж,
 *         возвращает этот картридж как "вытолкнутый" предмет — его мы выбрасываем наружу.</li>
 * </ul>
 */
public final class BigCannonBreechFeeder {

    private static final Logger LOGGER = LoggerFactory.getLogger("cbc_autotarget/BigCannonBreechFeeder");

    private BigCannonBreechFeeder() {}

    /**
     * Пытается зарядить снаряды/картриджи в казённик Quick-Firing Breech данной пушки
     * из инвентаря контроллера. Обходит все непустые слоты, для каждого пробует передать
     * ровно 1 предмет. Если CBC в ответ вернул другой предмет (пустой картридж, оставшийся
     * после выстрела) — он выбрасывается наружу позади затвора.
     *
     * @return true, если было выполнено хотя бы одно действие (зарядка и/или выброс гильзы).
     */
    public static boolean feed(MountedBigCannonContraption bigCannon, PitchOrientedContraptionEntity poce,
                                ItemStackHandler inventory) {
        if (bigCannon == null || poce == null) return false;

        boolean any = false;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty() || !isBigCannonMunition(stack)) continue;

            ItemStack probe = stack.copyWithCount(1);
            ItemStack result;
            try {
                result = CannonMountPoint.bigCannonInsert(probe, false, bigCannon, poce);
            } catch (Exception e) {
                LOGGER.debug("[feed] bigCannonInsert threw for {}", stack, e);
                continue;
            }

            // Ничего не изменилось — CBC отклонил предмет (казённик занят/закрыт/не тот порядок).
            if (ItemStack.matches(result, probe)) continue;

            stack.shrink(1);
            inventory.setStackInSlot(slot, stack);
            any = true;

            // Пустой картридж, вытолкнутый при зарядке нового снаряда — выбрасываем наружу.
            if (!result.isEmpty()) {
                ejectSpentCartridge(poce, bigCannon, result);
            }
        }
        return any;
    }

    private static boolean isBigCannonMunition(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof BigCannonMunitionBlock;
    }

    /**
     * Выбрасывает пустой картридж наружу позади затвора — так же, как это делает ручное
     * открытие Quick-Firing Breech игроком (см. {@code QuickfiringBreechBlock}).
     */
    private static void ejectSpentCartridge(PitchOrientedContraptionEntity poce,
                                             MountedBigCannonContraption bigCannon, ItemStack spent) {
        Level level = poce.level();
        if (level == null || level.isClientSide) return;

        Direction pushDirection = bigCannon.initialOrientation();
        BlockPos breechLocalPos = bigCannon.getStartPos().relative(pushDirection.getOpposite());

        Vec3 normal = new Vec3(pushDirection.getOpposite().step());
        Vec3 dir = poce.applyRotation(normal, 0);
        Vec3 ejectLocalPos = Vec3.atCenterOf(breechLocalPos).add(normal.scale(1.1));
        Vec3 globalPos = poce.toGlobalVector(ejectLocalPos, 0);
        Vec3 vel = dir.scale(0.075);

        ItemEntity item = new ItemEntity(level, globalPos.x, globalPos.y, globalPos.z, spent, vel.x, vel.y, vel.z);
        item.setPickUpDelay(CBCConfigs.server().munitions.quickFiringBreechItemPickupDelay.get());
        level.addFreshEntity(item);
    }
}
