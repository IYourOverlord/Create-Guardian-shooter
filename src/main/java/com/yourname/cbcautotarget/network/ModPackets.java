package com.yourname.cbcautotarget.network;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class ModPackets {

    public static void register(IEventBus modBus) {
        modBus.addListener(ModPackets::onRegisterPayloads);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");

        // Controller — клиент → сервер
        r.playToServer(ToggleActivePacket.TYPE,        ToggleActivePacket.CODEC,        ToggleActivePacket::handle);
        r.playToServer(UpdateFilterPacket.TYPE,        UpdateFilterPacket.CODEC,        UpdateFilterPacket::handle);
        r.playToServer(UpdateWhitelistPacket.TYPE,     UpdateWhitelistPacket.CODEC,     UpdateWhitelistPacket::handle);
        r.playToServer(ToggleRotationAxisPacket.TYPE,  ToggleRotationAxisPacket.CODEC,  ToggleRotationAxisPacket::handle);
        r.playToServer(SetFireFrequencyPacket.TYPE,    SetFireFrequencyPacket.CODEC,    SetFireFrequencyPacket::handle);

        // Controller — сервер → клиент
        r.playToClient(SyncWhitelistPacket.TYPE, SyncWhitelistPacket.CODEC, SyncWhitelistPacket::handle);

        // Commander — клиент → сервер
        r.playToServer(ToggleCommanderPacket.TYPE,          ToggleCommanderPacket.CODEC,          ToggleCommanderPacket::handle);
        r.playToServer(UpdateCommanderFilterPacket.TYPE,    UpdateCommanderFilterPacket.CODEC,    UpdateCommanderFilterPacket::handle);
        r.playToServer(UpdateCommanderWhitelistPacket.TYPE, UpdateCommanderWhitelistPacket.CODEC, UpdateCommanderWhitelistPacket::handle);
        r.playToServer(UpdateCommanderKeyPacket.TYPE,       UpdateCommanderKeyPacket.CODEC,       UpdateCommanderKeyPacket::handle);

        // Commander — сервер → клиент
        r.playToClient(SyncCommanderDataPacket.TYPE, SyncCommanderDataPacket.CODEC, SyncCommanderDataPacket::handle);

        // MachineSoul — старый общий пакет (оставлен для совместимости)
        r.playToServer(SaveMachineSoulConfigPacket.TYPE, SaveMachineSoulConfigPacket.CODEC, SaveMachineSoulConfigPacket::handle);

        // MachineSoul — кнопка "Поиск цели" на главной странице
        r.playToServer(ToggleMachineSoulSearchPacket.TYPE, ToggleMachineSoulSearchPacket.CODEC, ToggleMachineSoulSearchPacket::handle);

        // MachineSoul — кнопка "Только на физической конструкции" на главной странице
        r.playToServer(ToggleMachineSoulSubLevelOnlyPacket.TYPE, ToggleMachineSoulSubLevelOnlyPacket.CODEC, ToggleMachineSoulSubLevelOnlyPacket::handle);

        // MachineSoul — кнопка "Таргет на игроков" на вкладке Target
        r.playToServer(ToggleMachineSoulTargetPlayersPacket.TYPE, ToggleMachineSoulTargetPlayersPacket.CODEC, ToggleMachineSoulTargetPlayersPacket::handle);

        // MachineSoul — новые пакеты по вкладкам
        r.playToServer(SwitchMachineSoulTabPacket.TYPE,    SwitchMachineSoulTabPacket.CODEC,    SwitchMachineSoulTabPacket::handle);
        r.playToServer(GoHomeMachineSoulPacket.TYPE,       GoHomeMachineSoulPacket.CODEC,       GoHomeMachineSoulPacket::handle);
        r.playToServer(SaveMachineSoulVisionPacket.TYPE,   SaveMachineSoulVisionPacket.CODEC,   SaveMachineSoulVisionPacket::handle);
        r.playToServer(SaveMachineSoulMovePacket.TYPE,     SaveMachineSoulMovePacket.CODEC,     SaveMachineSoulMovePacket::handle);
        r.playToServer(SaveMachineSoulActionPacket.TYPE,   SaveMachineSoulActionPacket.CODEC,   SaveMachineSoulActionPacket::handle);

        // MachineSoul — сервер → клиент
        r.playToClient(SyncMachineSoulStatusPacket.TYPE, SyncMachineSoulStatusPacket.CODEC, SyncMachineSoulStatusPacket::handle);

        // MachineSoul — фильтр игроков (вкладка Target)
        r.playToServer(UpdateMachineSoulPlayerFilterPacket.TYPE, UpdateMachineSoulPlayerFilterPacket.CODEC, UpdateMachineSoulPlayerFilterPacket::handle);
        r.playToClient(SyncMachineSoulPlayerFilterPacket.TYPE,   SyncMachineSoulPlayerFilterPacket.CODEC,  SyncMachineSoulPlayerFilterPacket::handle);
        r.playToServer(UpdateMachineSoulCommanderFilterPacket.TYPE, UpdateMachineSoulCommanderFilterPacket.CODEC, UpdateMachineSoulCommanderFilterPacket::handle);
        r.playToClient(SyncMachineSoulCommanderFilterPacket.TYPE,   SyncMachineSoulCommanderFilterPacket.CODEC,  SyncMachineSoulCommanderFilterPacket::handle);
    }
}