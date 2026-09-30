package com.yourname.cbcautotarget.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity.CommandRole;
import com.yourname.cbcautotarget.blockentity.MachineSoulBlockEntity.Tab;
import com.yourname.cbcautotarget.menu.MachineSoulMoveMenu;
import com.yourname.cbcautotarget.network.BeginSoulDirectBindPacket;
import com.yourname.cbcautotarget.network.SaveMachineSoulMovePacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.LinkedHashMap;
import java.util.Map;

/** Movement frequency cells arranged around the fixed guardian. */
public class MachineSoulMoveScreen extends BaseMachineSoulScreen<MachineSoulMoveMenu> {
    private static final int GUI_H=235;
    // Six pairs: upper-left, upper-right, middle-left, middle-right, lower-left, lower-right.
    private static final int[] CELL_X={28,218,12,234,28,218};
    private static final int[] CELL_Y={30,30,78,78,126,126};
    private static final String[] LABELS={"FORWARD","BACKWARD","LEFT","RIGHT","UP","DOWN"};
    // Кнопка «+» справа от пары слотов: привязка прямого редстоун-выхода к стороне блока.
    private static final int PLUS_SIZE=13,PLUS_DX=44,PLUS_DY=2;
    public MachineSoulMoveScreen(MachineSoulMoveMenu m,Inventory i,Component t){super(m,i,t,Tab.MOVEMENT,m.blockPos,GUI_H);}
    @Override protected int getInvYBase(){return MachineSoulMoveMenu.INV_Y_BASE;}
    @Override protected int getInvX(){return MachineSoulMoveMenu.INV_X;}
    @Override protected void renderContent(GuiGraphics g,int lx,int ty,int mx,int my){
        for(int r=0;r<6;r++){int x=lx+CELL_X[r],y=ty+CELL_Y[r];
            g.drawCenteredString(font,LABELS[r],x+19,y-10,COL_TEXT_DIM);
            drawSlotBg(g,x,y);drawSlotBg(g,x+22,y);
            if(mx>=x&&mx<x+38&&my>=y&&my<y+18)g.renderTooltip(font,Component.literal(LABELS[r]+" Redstone Link frequencies"),mx,my);
            drawPlusButton(g,r,mx,my);
        }
        // Navigation is drawn by BaseMachineSoulScreen at header level.
        renderGhostItems(g, lx, ty, mx, my);
    }

    private boolean isPlusHovered(int r,int mx,int my){
        int x=leftPos+CELL_X[r]+PLUS_DX,y=topPos+CELL_Y[r]+PLUS_DY;
        return mx>=x&&mx<x+PLUS_SIZE&&my>=y&&my<y+PLUS_SIZE;
    }

    private void drawPlusButton(GuiGraphics g,int r,int mx,int my){
        int x=leftPos+CELL_X[r]+PLUS_DX,y=topPos+CELL_Y[r]+PLUS_DY;
        boolean hov=isPlusHovered(r,mx,my);
        g.fill(x,y,x+PLUS_SIZE,y+PLUS_SIZE,hov?COL_SAVE_HOVER_BG:COL_SAVE_BG);
        drawBorder(g,x,y,PLUS_SIZE,PLUS_SIZE,COL_SAVE_BORDER);
        int cx=x+PLUS_SIZE/2,cy=y+PLUS_SIZE/2,col=hov?COL_ACCENT2:COL_ACCENT;
        g.fill(cx-3,cy,cx+4,cy+1,col);
        g.fill(cx,cy-3,cx+1,cy+4,col);
        if(hov)g.renderTooltip(font,Component.translatable("gui.cbc_autotarget.soul.direct.tooltip"),mx,my);
    }

    private void renderGhostItems(GuiGraphics g,int lx,int ty,int mx,int my){
        for (int i = 0; i < MachineSoulMoveMenu.FREQ_SLOTS; i++) {
            Slot slot = menu.slots.get(i);
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;

            int role = i / 2, pair = i % 2;
            int x = lx + MachineSoulMoveMenu.slotX(role, pair);
            int y = ty + MachineSoulMoveMenu.slotY(role);

            boolean hov = mx >= x && mx < x + 16 && my >= y && my < y + 16;
            if (hov) g.fill(x, y, x + 16, y + 16, COL_GHOST_HOVER);

            RenderSystem.enableBlend();
            RenderSystem.setShaderColor(1f, 1f, 1f, 0.5f);
            g.renderItem(stack, x, y);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.disableBlend();
            g.fill(x, y, x + 16, y + 16, COL_GHOST_OVERLAY);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (int r = 0; r < 6; r++) {
                if (isPlusHovered(r, (int) mx, (int) my)) {
                    PacketDistributor.sendToServer(new BeginSoulDirectBindPacket(blockPos, r));
                    onClose();
                    return true;
                }
            }
        }
        for (int i = 0; i < MachineSoulMoveMenu.FREQ_SLOTS; i++) {
            int role = i / 2, pair = i % 2;
            int x = leftPos + MachineSoulMoveMenu.slotX(role, pair);
            int y = topPos  + MachineSoulMoveMenu.slotY(role);
            if (mx >= x && mx < x + 16 && my >= y && my < y + 16) {
                ItemStack carried = menu.getCarried();
                menu.setFreqItem(i, (button == 1 || carried.isEmpty()) ? ItemStack.EMPTY : carried);
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override protected boolean onSaveClicked(){
        Map<CommandRole,ItemStack[]> map=new LinkedHashMap<>();
        for(int r=0;r<6;r++)
            map.put(MachineSoulMoveMenu.MOVE_ROLES[r],new ItemStack[]{
                    menu.getFreqItem(r*2).copy(),
                    menu.getFreqItem(r*2+1).copy()});
        PacketDistributor.sendToServer(new SaveMachineSoulMovePacket(blockPos,map));
        onClose();
        return true;
    }
}