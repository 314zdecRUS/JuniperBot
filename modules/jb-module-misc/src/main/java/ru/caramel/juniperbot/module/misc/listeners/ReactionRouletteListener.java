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
package ru.caramel.juniperbot.module.misc.listeners;

import net.dv8tion.jda.core.Permission;
import net.dv8tion.jda.core.entities.Emote;
import net.dv8tion.jda.core.entities.Guild;
import net.dv8tion.jda.core.entities.MessageType;
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.RandomUtils;
import org.springframework.beans.factory.annotation.Autowired;
import ru.caramel.juniperbot.core.event.listeners.DiscordEventListener;
import ru.caramel.juniperbot.core.event.DiscordEvent;
import ru.caramel.juniperbot.module.misc.persistence.entity.ReactionRoulette;
import ru.caramel.juniperbot.module.misc.service.ReactionRouletteService;

import java.util.List;
import java.util.stream.Collectors;

@DiscordEvent
public class ReactionRouletteListener extends DiscordEventListener {

    @Autowired
    private ReactionRouletteService reactionRouletteService;

    @Override
    public void onGuildMessageReceived(GuildMessageReceivedEvent event) {
        Guild guild = event.getGuild();
        if (event.getAuthor() == null
                || event.getAuthor().isBot()
                || event.getMessage().getType() != MessageType.DEFAULT
                || guild.getSelfMember().equals(event.getMember())) {
            return;
        }
        ReactionRoulette roulette = reactionRouletteService.getByGuildId(guild.getIdLong());
        if (roulette == null
                || !roulette.isEnabled()
                || CollectionUtils.isEmpty(roulette.getSelectedEmotes())) {
            return;
        }

        if (CollectionUtils.isNotEmpty(roulette.getIgnoredChannels())
                && roulette.getIgnoredChannels().contains(event.getChannel().getIdLong())) {
            return;
        }

        if (RandomUtils.nextLong(1, 1000) <= roulette.getPercent() * 10) {
            List<Emote> emotes = guild.getEmotes().stream()
                    .filter(e -> !e.isManaged() && roulette.getSelectedEmotes().contains(e.getId()))
                    .collect(Collectors.toList());
            if (CollectionUtils.isEmpty(emotes)) {
                return;
            }

            Emote emote = emotes.get(RandomUtils.nextInt(0, emotes.size()));
            if (roulette.isReaction() && guild.getSelfMember().hasPermission(event.getChannel(),
                    Permission.MESSAGE_ADD_REACTION) && emote.canInteract(event.getJDA().getSelfUser(), event.getChannel())) {
                try {
                    event.getMessage().addReaction(emote).queue();
                } catch (Exception e) {
                    // ignore
                }
            } else if (guild.getSelfMember().hasPermission(event.getChannel(), Permission.MESSAGE_WRITE)) {
                event.getChannel().sendMessage(emote.getAsMention()).queue();
            }
        }
    }
}
