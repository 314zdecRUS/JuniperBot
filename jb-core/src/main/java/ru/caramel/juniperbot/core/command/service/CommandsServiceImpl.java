/*
 * This file is part of JuniperBotJ.
 *
 * JuniperBotJ is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * JuniperBotJ is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with JuniperBotJ. If not, see <http://www.gnu.org/licenses/>.
 */
package ru.caramel.juniperbot.core.command.service;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.Permission;
import net.dv8tion.jda.core.entities.*;
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.ocpsoft.prettytime.PrettyTime;
import org.ocpsoft.prettytime.units.JustNow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.caramel.juniperbot.core.command.model.*;
import ru.caramel.juniperbot.core.command.persistence.CommandConfig;
import ru.caramel.juniperbot.core.common.model.exception.DiscordException;
import ru.caramel.juniperbot.core.common.model.exception.ValidationException;
import ru.caramel.juniperbot.core.common.persistence.GuildConfig;
import ru.caramel.juniperbot.core.common.service.ConfigService;
import ru.caramel.juniperbot.core.event.service.ContextService;
import ru.caramel.juniperbot.core.message.service.MessageService;
import ru.caramel.juniperbot.core.metrics.service.DiscordMetricsRegistry;
import ru.caramel.juniperbot.core.metrics.service.StatisticsService;
import ru.caramel.juniperbot.core.moderation.persistence.ModerationConfig;
import ru.caramel.juniperbot.core.moderation.service.ModerationService;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class CommandsServiceImpl implements CommandsService {

    @Autowired
    private ConfigService configService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private ContextService contextService;

    @Autowired
    private CommandsHolderService commandsHolderService;

    @Autowired
    private CommandConfigService commandConfigService;

    @Autowired
    private StatisticsService statisticsService;

    @Autowired
    private ModerationService moderationService;

    @Autowired
    private DiscordMetricsRegistry registry;

    private Cache<Long, BotContext> contexts = CacheBuilder.newBuilder()
            .expireAfterAccess(1, TimeUnit.DAYS)
            .build();

    private Map<Long, CoolDownHolder> coolDownHolderMap = new ConcurrentHashMap<>();

    private Meter executions;

    private Counter counter;

    private Set<CommandHandler> handlers = new TreeSet<>(Comparator.comparingInt(CommandHandler::getPriority));

    @PostConstruct
    public void init() {
        executions = statisticsService.getMeter(EXECUTIONS_METER);
        counter = statisticsService.getCounter(EXECUTIONS_COUNTER);
    }

    @Override
    public void clear(Guild guild) {
        coolDownHolderMap.remove(guild.getIdLong());
    }

    @Override
    @Transactional
    public void onMessageReceived(GuildMessageReceivedEvent event) {
        if (event.getAuthor().isBot() || event.getMessage().getType() != MessageType.DEFAULT) {
            return;
        }
        if (!sendMessage(event, this, commandsHolderService::isAnyCommand)) {
            for (CommandHandler handler : handlers) {
                if (handler.handleMessage(event)) {
                    break;
                }
            }
        }
    }

    @Override
    public boolean sendMessage(GuildMessageReceivedEvent event, CommandSender sender, Function<String, Boolean> commandCheck) {
        JDA jda = event.getJDA();
        String content = event.getMessage().getContentRaw().trim();
        if (StringUtils.isEmpty(content)) {
            return false;
        }

        String prefix = null;
        String input = content;
        boolean usingMention = false;

        if (event.getMessage().isMentioned(jda.getSelfUser())) {
            String mention = jda.getSelfUser().getAsMention();
            if (!(usingMention = content.startsWith(mention))) {
                mention = String.format("<@!%s>", jda.getSelfUser().getId());
                usingMention = content.startsWith(mention);
            }
            if (usingMention) {
                prefix = mention;
                input = content.substring(prefix.length()).trim();
            }
        }

        String firstPart = input.split("\\s+", 2)[0].trim();
        if (commandCheck != null && !commandCheck.apply(firstPart)) {
            return false;
        }

        GuildConfig guildConfig = configService.getOrCreate(event.getGuild());

        if (!usingMention) {
            prefix = guildConfig != null ? guildConfig.getPrefix() : configService.getDefaultPrefix();
            if (prefix.length() > content.length()) {
                return true;
            }
            input = content.substring(prefix.length()).trim();
        }
        if (content.toLowerCase().startsWith(prefix.toLowerCase())) {
            String[] args = input.split("\\s+", 2);
            input = args.length > 1 ? args[1] : "";
            return sender.sendCommand(event, input, args[0], guildConfig);
        }
        return true;
    }

    @Override
    @Synchronized
    public void registerHandler(CommandHandler handler) {
        handlers.add(handler);
    }

    @Override
    public boolean sendCommand(GuildMessageReceivedEvent event, String content, String key, GuildConfig guildConfig) {
        TextChannel channel = event.getChannel();
        String locale = guildConfig != null ? guildConfig.getCommandLocale() : null;
        Command command = commandsHolderService.getByLocale(key, locale);
        if (command == null) {
            return false;
        }
        String rawKey = command.getKey();
        if (rawKey == null) {
            return false;
        }

        CommandConfig commandConfig = event.getGuild() != null ? commandConfigService.findByKey(event.getGuild().getIdLong(), rawKey) : null;
        if (!isApplicable(command, commandConfig, event.getAuthor(), event.getMember(), channel)) {
            return false;
        }

        if (commandConfig != null && isRestricted(event, commandConfig)) {
            return true;
        }
        Permission[] permissions = command.getPermissions();
        if (permissions != null && permissions.length > 0) {
            Member self = event.getGuild().getSelfMember();
            if (self != null && !self.hasPermission(channel, permissions)) {
                String list = Stream.of(permissions)
                        .filter(e -> !self.hasPermission(channel, e))
                        .map(e -> messageService.getEnumTitle(e))
                        .collect(Collectors.joining("\n"));
                if (self.hasPermission(channel, Permission.MESSAGE_WRITE)) {
                    restrictMessage(channel, "discord.command.insufficient.permissions", list);
                }
                return true;
            }
        }

        BotContext context = getContext(event.getChannel());
        context.setConfig(guildConfig);

        statisticsService.doWithTimer(getTimer(event.getJDA(), command), () -> {
            try {
                log.info("Invoke command {} for userId={}, guildId={}",
                        command.getClass().getSimpleName(),
                        event.getAuthor() != null ? event.getAuthor().getId() : null,
                        event.getGuild() != null ? event.getGuild().getId() : null);
                command.doCommand(event, context, content);
                counter.inc();
                if (commandConfig != null && commandConfig.isDeleteSource()
                        && event.getGuild().getSelfMember().hasPermission(event.getChannel(), Permission.MESSAGE_MANAGE)) {
                    messageService.delete(event.getMessage());
                }
            } catch (ValidationException e) {
                messageService.onEmbedMessage(event.getChannel(), e.getMessage(), e.getArgs());
            } catch (DiscordException e) {
                messageService.onError(event.getChannel(),
                        messageService.hasMessage(e.getMessage()) ? e.getMessage() : "discord.global.error");
                log.error("Command {} execution error", key, e);
            } finally {
                executions.mark();
                registry.incrementCommand(command);
            }
        });
        return true;
    }

    @Override
    @Transactional
    public boolean isRestricted(GuildMessageReceivedEvent event, CommandConfig commandConfig) {
        if (isRestricted(commandConfig, event.getChannel())) {
            resultEmotion(event, "✋", null);
            messageService.onTempEmbedMessage(event.getChannel(), 10, "discord.command.restricted.channel");
            return true;
        }
        if (isRestricted(commandConfig, event.getMember())) {
            resultEmotion(event, "✋", null);
            messageService.onTempEmbedMessage(event.getChannel(), 10, "discord.command.restricted.roles");
            return true;
        }
        if (event.getMember() != null && commandConfig.getCoolDownMode() != CoolDownMode.NONE) {
            if (CollectionUtils.isEmpty(commandConfig.getCoolDownIgnoredRoles()) ||
                    event.getMember().getRoles().stream().noneMatch(e -> commandConfig.getCoolDownIgnoredRoles().contains(e.getIdLong()))) {
                ModerationConfig moderationConfig = moderationService.get(event.getGuild());
                if (!moderationService.isModerator(event.getMember())
                        || (moderationConfig != null && !moderationConfig.isCoolDownIgnored())) {
                    CoolDownHolder holder = coolDownHolderMap.computeIfAbsent(event.getGuild().getIdLong(), CoolDownHolder::new);
                    long duration = holder.perform(event, commandConfig);
                    if (duration > 0) {
                        resultEmotion(event, "\uD83D\uDD5C", null);
                        Date date = new Date();
                        date.setTime(date.getTime() + duration);

                        PrettyTime time = new PrettyTime(contextService.getLocale());
                        time.removeUnit(JustNow.class);
                        messageService.onTempEmbedMessage(event.getChannel(), 10,
                                "discord.command.restricted.cooldown", time.format(date));
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public boolean isRestricted(CommandConfig commandConfig, TextChannel channel) {
        if (channel == null || commandConfig == null) {
            return false;
        }
        if (CollectionUtils.isNotEmpty(commandConfig.getAllowedChannels())
                && !commandConfig.getAllowedChannels().contains(channel.getIdLong())) {
            return true;
        }
        if (CollectionUtils.isNotEmpty(commandConfig.getIgnoredChannels())
                && commandConfig.getIgnoredChannels().contains(channel.getIdLong())) {
            return true;
        }
        return false;
    }

    @Override
    public boolean isRestricted(CommandConfig commandConfig, Member member) {
        if (member == null || commandConfig == null) {
            return false;
        }
        if (CollectionUtils.isNotEmpty(commandConfig.getAllowedRoles())
                && member.getRoles().stream().noneMatch(e -> commandConfig.getAllowedRoles().contains(e.getIdLong()))) {
            return true;
        }
        if (CollectionUtils.isNotEmpty(commandConfig.getIgnoredRoles())
                && member.getRoles().stream().anyMatch(e -> commandConfig.getIgnoredRoles().contains(e.getIdLong()))) {
            return true;
        }
        return false;
    }

    private void restrictMessage(TextChannel textChannel, String titleCode, String messageCode) {
        if (textChannel.getGuild().getSelfMember().hasPermission(textChannel, Permission.MESSAGE_EMBED_LINKS)) {
            messageService.onError(textChannel, titleCode, messageCode);
        } else {
            String title = messageService.getMessage(titleCode);
            String message = messageService.getMessage(messageCode);
            messageService.sendMessageSilent(textChannel::sendMessage, title + "\n\n" + message);
        }
    }

    @Override
    public boolean isApplicable(Command command, CommandConfig commandConfig, User user, Member member, TextChannel channel) {
        if (command.getAnnotation() == null) {
            return false;
        }
        if (commandConfig != null && commandConfig.isDisabled()) {
            return false;
        }
        Guild guild = member != null ? member.getGuild() : null;
        if (channel != null) {
            guild = channel.getGuild();
        }
        return command.isAvailable(user, member, guild);
    }

    public boolean isRestricted(String rawKey, TextChannel channel, Member member) {
        CommandConfig config = commandConfigService.findByKey(channel.getGuild().getIdLong(), rawKey);
        Command command = commandsHolderService.getCommands().get(rawKey);
        if (command == null) {
            return true;
        }

        if (!isApplicable(command, config, member != null ? member.getUser() : null, member, channel)) {
            return true;
        }

        if (isRestricted(config, channel)) {
            return true;
        }

        if (member != null && isRestricted(config, member)) {
            return true;
        }
        return false;
    }

    @Override
    public void resultEmotion(GuildMessageReceivedEvent message, String emoji, String messageCode, Object... args) {
        try {
            if (message.getGuild() == null || message.getGuild().getSelfMember().hasPermission(message.getChannel(),
                    Permission.MESSAGE_ADD_REACTION)) {
                try {
                    message.getMessage().addReaction(emoji).queue();
                    return;
                } catch (Exception e) {
                    // fall down and add emoticon as message
                }
            }
            String text = emoji;
            if (StringUtils.isNotEmpty(messageCode) && messageService.hasMessage(messageCode)) {
                text = messageService.getMessage(messageCode, args);
            }
            messageService.sendMessageSilent(message.getChannel()::sendMessage, text);
        } catch (Exception e) {
            log.error("Add emotion error", e);
        }
    }

    private Timer getTimer(JDA jda, Command command) {
        int shard = -1;
        if (jda != null && jda.getShardInfo() != null) {
            shard = jda.getShardInfo().getShardId();
        }
        return statisticsService.getTimer(String.format("commands/shard.%d/%s", shard, command.getKey()));
    }

    private BotContext getContext(MessageChannel channel) {
        try {
            return contexts.get(channel.getIdLong(), BotContext::new);
        } catch (ExecutionException e) {
            return new BotContext();
        }
    }
}