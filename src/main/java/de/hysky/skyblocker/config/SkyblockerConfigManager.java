package de.hysky.skyblocker.config;

import com.google.gson.FieldNamingPolicy;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import de.hysky.skyblocker.SkyblockerMod;
import de.hysky.skyblocker.config.categories.*;
import de.hysky.skyblocker.debug.Debug;
import de.hysky.skyblocker.mixins.accessors.HandledScreenAccessor;
import de.hysky.skyblocker.utils.scheduler.Scheduler;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.config.v2.api.ConfigClassHandler;
import dev.isxander.yacl3.config.v2.api.serializer.GsonConfigSerializerBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.command.CommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import javax.annotation.Nullable;
import java.lang.StackWalker.Option;
import java.nio.file.Path;
import java.util.stream.Stream;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;

public class SkyblockerConfigManager {

    public static final int CONFIG_VERSION = 3;
    private static final Path CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("skyblocker.json");
    private static final ConfigClassHandler<SkyblockerConfig> HANDLER = ConfigClassHandler.createBuilder(SkyblockerConfig.class)
            .serializer(config -> GsonConfigSerializerBuilder.create(config)
                    .setPath(CONFIG_FILE)
                    .setJson5(false)
                    .appendGsonBuilder(builder -> builder
                            .setFieldNamingPolicy(FieldNamingPolicy.IDENTITY)
                            .registerTypeHierarchyAdapter(Identifier.class, new Identifier.Serializer()))
                    .build())
            .build();

    public static SkyblockerConfig get() {
        return HANDLER.instance();
    }

    /**
     * This method is caller sensitive and can only be called by the mod initializer,
     * this is enforced.
     */
    public static void init() {
        if (StackWalker.getInstance(Option.RETAIN_CLASS_REFERENCE).getCallerClass() != SkyblockerMod.class) {
            throw new RuntimeException("Skyblocker: Called config init from an illegal place!");
        }

        HANDLER.load();
        ClientCommandRegistrationCallback.EVENT.register(((dispatcher, registryAccess) -> dispatcher
                .register(ClientCommandManager
                        .literal(SkyblockerMod.NAMESPACE)
                        .then(optionsLiteral("config"))
                        .then(optionsLiteral("options")))));

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof GenericContainerScreen genericContainerScreen && screen.getTitle().getString().equals("SkyBlock Menu")) {
                Screens.getButtons(screen).add(ButtonWidget
                        .builder(Text.literal("\uD83D\uDD27"), buttonWidget -> client.setScreen(createGUI(screen)))
                        .dimensions(((HandledScreenAccessor) genericContainerScreen).getX() + ((HandledScreenAccessor) genericContainerScreen).getBackgroundWidth() - 16, ((HandledScreenAccessor) genericContainerScreen).getY() + 4, 12, 12)
                        .tooltip(Tooltip.of(Text.translatable("skyblocker.config.title")))
                        .build());
            }
        });
    }

    public static void save() {
        HANDLER.save();
    }

    public static Screen createGUI(Screen parent) {
        return createGUI(parent, null);
    }

    // Overloaded method allows for specifying a category to open to
    public static Screen createGUI(Screen parent, @Nullable String category) {
        return YetAnotherConfigLib.create(HANDLER, (defaults, config, builder) -> {
            builder.title(Text.translatable("skyblocker.config.title"))
                    .category(GeneralCategory.create(defaults, config))
                    .category(UIAndVisualsCategory.create(defaults, config))
                    .category(HelperCategory.create(defaults, config))
                    .category(DungeonsCategory.create(defaults, config))
                    //.category(ForagingCategory.create(defaults, config))
                    .category(CrimsonIsleCategory.create(defaults, config))
                    .category(MiningCategory.create(defaults, config))
                    .category(FarmingCategory.create(defaults, config))
                    .category(OtherLocationsCategory.create(defaults, config))
                    .category(SlayersCategory.create(defaults, config))
                    .category(ChatCategory.create(defaults, config))
                    .category(QuickNavigationCategory.create(defaults, config))
                    .category(EventNotificationsCategory.create(defaults, config))
                    .category(MiscCategory.create(defaults, config));
            if (Debug.debugEnabled()) {
                builder.category(DebugCategory.create(defaults, config));
            }

            // Default method will skip the initial screen to avoid redundant checks
            if (category != null) setInitialConfigScreen(builder, category);

            return builder;

        }).generateScreen(parent);
    }


    public static void setInitialConfigScreen(YetAnotherConfigLib.Builder builder, String category) {
        builder.screenInit(screen ->
                screen.tabManager.setCurrentTab(screen.tabNavigationBar.getTabs().stream().filter(tab ->
                        tab.getTitle().getString().toLowerCase().matches(category.toLowerCase() + ".*")

                ).findFirst().orElse(screen.tabNavigationBar.getTabs().getFirst()), false)
        );
    }

    /**
     * Registers an options command with the given name. Used for registering both options and config as valid commands.
     *
     * @param name the name of the command node
     * @return the command builder
     */
    private static LiteralArgumentBuilder<FabricClientCommandSource> optionsLiteral(String name) {
        // Don't immediately open the next screen as it will be closed by ChatScreen right after this command is executed
        return ClientCommandManager.literal(name).executes(Scheduler.queueOpenScreenCommand(() -> createGUI(null)))
                // If a category is specified, open to that category using overloaded createGUI method
                .then(argument("category", StringArgumentType.word())
                        .suggests((ctx, builder) ->
                                CommandSource.suggestMatching(Stream.of("general", "ui", "helper", "dungeons", "crimson", "mining", "farming", "other", "slayers", "chat", "quick", "event", "misc"), builder))
                        .executes(ctx ->
                                Scheduler.queueOpenScreenCommand(() ->
                                        createGUI(null, StringArgumentType.getString(ctx, "category"))).run(ctx)));
    }
}
