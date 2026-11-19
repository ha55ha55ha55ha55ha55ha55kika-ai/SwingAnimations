package com.kitkatvisuals.link;

import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.UUID;

/**
 * Канал связи между клиентами мода: kitkatvisuals:link.
 * Клиент → сервер: «сырое» тело (формат см. ClientLink.encode). Сервер-реле (плагин) пересылает его остальным,
 * добавив в начало UUID отправителя. Без плагина на сервере канал недоступен и клиент ничего не шлёт.
 */
public final class LinkPayloads {
    public static final Identifier CHANNEL = Identifier.of("kitkatvisuals", "link");

    private LinkPayloads() {}

    public record Up(byte[] body) implements CustomPayload {
        public static final Id<Up> ID = new Id<>(CHANNEL);
        public static final net.minecraft.network.codec.PacketCodec<net.minecraft.network.RegistryByteBuf, Up> CODEC =
                CustomPayload.codecOf((value, buf) -> buf.writeBytes(value.body()), buf -> {
                    byte[] b = new byte[buf.readableBytes()];
                    buf.readBytes(b);
                    return new Up(b);
                });

        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    public record Down(UUID sender, byte[] body) implements CustomPayload {
        public static final Id<Down> ID = new Id<>(CHANNEL);
        public static final net.minecraft.network.codec.PacketCodec<net.minecraft.network.RegistryByteBuf, Down> CODEC =
                CustomPayload.codecOf((value, buf) -> {
                    buf.writeUuid(value.sender());
                    buf.writeBytes(value.body());
                }, buf -> {
                    if (buf.readableBytes() < 16) { // не наш формат: съедаем остаток и игнорируем
                        buf.skipBytes(buf.readableBytes());
                        return new Down(new UUID(0L, 0L), new byte[0]);
                    }
                    UUID id = buf.readUuid();
                    byte[] b = new byte[buf.readableBytes()];
                    buf.readBytes(b);
                    return new Down(id, b);
                });

        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }
}
