package com.kitkatvisuals.link;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** Клиентские команды (не уходят на сервер): /pronoob и /friend ... */
final class FriendCommands {
    private FriendCommands() {}

    private static final SuggestionProvider<FabricClientCommandSource> ONLINE =
            (ctx, b) -> CommandSource.suggestMatching(FriendLink.suggestOnline(), b);
    private static final SuggestionProvider<FabricClientCommandSource> REQUESTS =
            (ctx, b) -> CommandSource.suggestMatching(FriendLink.suggestRequests(), b);
    private static final SuggestionProvider<FabricClientCommandSource> FRIENDS =
            (ctx, b) -> CommandSource.suggestMatching(FriendLink.suggestFriends(), b);

    private static MinecraftClient mc() {
        return MinecraftClient.getInstance();
    }

    static void register(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(literal("pronoob").executes(ctx -> {
            FriendLink.showPronoob(mc());
            return 1;
        }));

        d.register(literal("friend")
                .executes(ctx -> {
                    usage();
                    return 1;
                })
                .then(literal("add")
                        .then(argument("nick", StringArgumentType.word()).suggests(ONLINE).executes(ctx -> {
                            FriendLink.sendRequest(mc(), StringArgumentType.getString(ctx, "nick"));
                            return 1;
                        })))
                .then(literal("accept")
                        .executes(ctx -> {
                            FriendLink.respond(mc(), null, true);
                            return 1;
                        })
                        .then(argument("nick", StringArgumentType.word()).suggests(REQUESTS).executes(ctx -> {
                            FriendLink.respond(mc(), StringArgumentType.getString(ctx, "nick"), true);
                            return 1;
                        })))
                .then(literal("deny")
                        .executes(ctx -> {
                            FriendLink.respond(mc(), null, false);
                            return 1;
                        })
                        .then(argument("nick", StringArgumentType.word()).suggests(REQUESTS).executes(ctx -> {
                            FriendLink.respond(mc(), StringArgumentType.getString(ctx, "nick"), false);
                            return 1;
                        })))
                .then(literal("remove")
                        .then(argument("nick", StringArgumentType.word()).suggests(FRIENDS).executes(ctx -> {
                            FriendLink.remove(mc(), StringArgumentType.getString(ctx, "nick"));
                            return 1;
                        })))
                .then(literal("list").executes(ctx -> {
                    FriendLink.showFriends(mc());
                    return 1;
                }))
                .then(literal("mark")
                        .executes(ctx -> {
                            FriendLink.placeMark(mc(), "");
                            return 1;
                        })
                        .then(argument("text", StringArgumentType.greedyString()).executes(ctx -> {
                            FriendLink.placeMark(mc(), StringArgumentType.getString(ctx, "text"));
                            return 1;
                        })))
                .then(literal("unmark").executes(ctx -> {
                    FriendLink.clearMarks(mc());
                    return 1;
                })));
    }

    private static void usage() {
        MinecraftClient c = mc();
        FriendLink.say(c, Text.literal("Команды друзей:").formatted(Formatting.WHITE));
        String[] lines = {
                "/friend add <ник>      запрос в друзья (игрок должен быть с визуалом)",
                "/friend accept [ник]   принять запрос",
                "/friend deny [ник]     отклонить запрос",
                "/friend remove <ник>   убрать из друзей",
                "/friend list           список друзей",
                "/friend mark [текст]   поставить метку (её видят друзья)",
                "/friend unmark         убрать мои метки",
                "/pronoob               кто сейчас с визуалом, где и на каком сервере"
        };
        for (String l : lines) c.inGameHud.getChatHud().addMessage(Text.literal(" " + l).formatted(Formatting.GRAY));
    }
}
